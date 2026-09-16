package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Discovery by combination (docs/DESIGN_BRIDGES.md): two areas of the library that no source read together,
 * joined by specific terms both areas' claims share. Swanson's fish oil and Raynaud's, joined through blood
 * viscosity and platelet aggregation. The sensor is mechanical and nominates pairs; the model reads only the
 * top pairs and writes each as a QUESTION, never a claim; the question goes to the open questions as a
 * proposal of type {@code bridge}, carrying the settings that found it; accepting it files a research run,
 * whose verified claims are the test of the bridge. Every proposal and its fate go to {@code catalog/bridges.jsonl}.
 *
 * <p>An area is a subject of the catalog with at least two findings. Its terms are the specific words and the
 * graph entities of its findings; a term on more than a fifth of the areas is not specific and bridges nothing.
 * Distance is hops on the map between the two subject nodes (they connect through the entities their findings
 * name); areas sharing a facet ({@code speech--…}) are neighbours.
 */
public final class Bridges {

    private static final ObjectMapper M = new ObjectMapper();

    /** The dials of one run. {@code sources}: library | peers | web. {@code reach}: low | medium | high. */
    public record Settings(Set<String> sources, String reach, boolean strict, String toward, List<String> away, String since, int perNight, String via) {
        public Settings(Set<String> sources, String reach, boolean strict, String toward, List<String> away, String since, int perNight) { this(sources, reach, strict, toward, away, since, perNight, "both"); }
        public static Settings defaults() { return new Settings(Set.of("library"), "medium", true, "", List.of(), "", PER_NIGHT, "both"); }
        public boolean terms() { return !via.equals("graph"); }
        public boolean graph() { return !via.equals("terms"); }
        public Settings with(String key, String value) {
            String v = value == null ? "" : value.strip();
            return switch (key) {
                case "sources" -> new Settings(new LinkedHashSet<>(List.of(v.toLowerCase(Locale.ROOT).split("[,\\s]+"))), reach, strict, toward, away, since, perNight, via);
                case "reach" -> new Settings(sources, v.toLowerCase(Locale.ROOT), strict, toward, away, since, perNight, via);
                case "strict" -> new Settings(sources, reach, !v.equalsIgnoreCase("false") && !v.equalsIgnoreCase("loose"), toward, away, since, perNight, via);
                case "loose" -> new Settings(sources, reach, false, toward, away, since, perNight, via);
                case "toward" -> new Settings(sources, reach, strict, v, away, since, perNight, via);
                case "away" -> new Settings(sources, reach, strict, toward, v.isEmpty() ? List.of() : List.of(v.toLowerCase(Locale.ROOT).split("[,;]\\s*")), since, perNight, via);
                case "since" -> new Settings(sources, reach, strict, toward, away, v, perNight, via);
                case "per_night", "propose" -> new Settings(sources, reach, strict, toward, away, since, v.isEmpty() ? perNight : Integer.parseInt(v), via);
                case "via" -> new Settings(sources, reach, strict, toward, away, since, perNight, v.isEmpty() ? "both" : v.toLowerCase(Locale.ROOT));
                default -> this;
            };
        }
        public String line() {
            return "sources=" + String.join(",", sources) + " reach=" + reach + " " + (strict ? "strict" : "loose") + " via=" + via
                    + (toward.isEmpty() ? "" : " toward=" + toward) + (away.isEmpty() ? "" : " away=" + String.join(",", away)) + (since.isEmpty() ? "" : " since=" + since);
        }
    }

    /** One area: a subject, its findings, the specific terms and the entities its findings name. */
    public record Area(String slug, String label, String description, List<Finding> findings, Set<String> terms, Set<String> entities, Set<String> subjectsNamed, Set<String> sources, Set<String> headTerms) {
        /** Words of the label, the description and the triple subjects: the area's own names, never bridge terms. */
        Set<String> labelWords() { Set<String> w = new HashSet<>(words(label)); w.addAll(words(description)); w.addAll(words(slug.replace('-', ' '))); for (String n : subjectsNamed) w.addAll(words(n)); return w; }
        /** The names a source would use for this area, normalised: the label, the catalogue's description, and what its claims are about (their triple subjects). */
        List<String> names() {
            List<String> out = new ArrayList<>(); out.add(norm(label));
            if (!description.isBlank() && !norm(description).equals(norm(label))) out.add(norm(description));
            // only a name specific enough to identify the area: a single ordinary word ("pressure", "trail", "geometry")
            // matches any source about anything, and five sources "named both" deep-sea ecology and bicycle frames (2026-09-15)
            for (String n : subjectsNamed) { String x = norm(n); if ((x.strip().contains(" ") || x.strip().length() >= NAME_CHARS) && !out.contains(x)) out.add(x); }
            return out;
        }
    }

    /** A candidate pair, ranked. {@code via}: the bridge terms; {@code hops}: distance on the map (-1 = not connected); {@code coMentions}: sources read that name both (0 = novel). */
    /**
     * A middle the library does not hold: something that bears on both areas, named by the model from outside the
     * shelves and then backed against BOTH sides by sources on the web. Swanson's open discovery — the closed form
     * needs the middle to appear in both literatures, and the middle that joins two distant fields usually appears in
     * neither (epic prosody and fermentation meet through acoustic energy, which neither side's claims name).
     */
    public record Join(String middle, String evidenceA, String evidenceC) { }

    public record Pair(Area a, Area c, List<String> via, double score, int hops, int coMentions, boolean random, List<String> paths, List<Join> joins) {
        public Pair(Area a, Area c, List<String> via, double score, int hops, int coMentions, boolean random, List<String> paths) { this(a, c, via, score, hops, coMentions, random, paths, List.of()); }
        public Pair(Area a, Area c, List<String> via, double score, int hops, int coMentions, boolean random) { this(a, c, via, score, hops, coMentions, random, List.of()); }
        /** Which sensor found it: terms, graph, web, or a combination. */
        public String sensor() {
            boolean t = via.stream().anyMatch(v -> !v.startsWith("→") && !v.startsWith("⇢")), g = !paths.isEmpty(), w = !joins.isEmpty();
            if (w && !t && !g) return "web";
            if (w) return (t && g ? "both" : g ? "graph" : "terms") + "+web";
            return t && g ? "both" : g ? "graph" : "terms";
        }
    }

    public record Proposal(String question, Pair pair, Settings settings, String by) { }

    static final int PER_NIGHT = org.researchzosho.Config.getInt("RESEARCHZOSHO_BRIDGES_PER_NIGHT", 3);
    /** A one-word area name must be at least this long to count as naming the area in a source; a chosen value, not a derived one. */
    static final int NAME_CHARS = org.researchzosho.Config.getInt("RESEARCHZOSHO_BRIDGES_NAME_CHARS", 10);
    /** A plain word must be at least this long to carry a bridge; a chosen value, not a derived one. */
    static final int WORD_CHARS = org.researchzosho.Config.getInt("RESEARCHZOSHO_BRIDGES_WORD_CHARS", 5);

    /** Findings an area needs before it can bridge. */
    static final int MIN_FINDINGS = 2;
    /** A term on more than this share of the areas is not specific. */
    static final double COMMON = 0.2;
    static final int STRICT_TERMS = 3, LOOSE_TERMS = 1;
    /** Pairs the model reads per run, before the per-night cut. */
    static final int TOP = 8;
    /**
     * Words that bridge nothing because they are everywhere. Two sources: function words, and the headwords of the
     * Academic Word List (Coxhead 2000), sublists 1–3 — the words most frequent across ALL academic disciplines, which
     * is exactly what makes them unable to tell two fields apart. Matched by stem, so "analysed", "analysis" and
     * "analytical" all fall under "analyse". Nothing here was added because a pair happened to bridge on it; the day
     * this list was grown by hand from failures ("limitations", then "consensus") it fitted those failures and nothing else.
     */
    static final Set<String> FUNCTION = new HashSet<>(List.of("about", "after", "also", "among", "because", "been", "before", "being", "between", "both",
            "could", "does", "each", "either", "from", "have", "here", "into", "just", "more", "most", "much", "must", "only", "other", "over", "same",
            "should", "since", "some", "such", "than", "that", "their", "them", "then", "there", "these", "they", "this", "those", "through", "under",
            "until", "upon", "very", "were", "what", "when", "where", "which", "while", "with", "within", "would", "your", "however", "although",
            "whether", "often", "many", "several", "first", "second", "later", "early", "known", "well", "high", "higher", "lower", "large", "small",
            "new", "old", "time", "year", "years", "people", "person", "work", "works", "way", "ways", "make", "made", "given", "take", "taken",
            "part", "parts", "case", "cases", "number", "numbers", "level", "levels", "rate", "rates", "value", "values", "effect", "effects",
            "example", "examples", "important", "different", "general", "particular", "possible", "likely", "common", "long", "short", "still",
            "even", "less", "least", "once", "without", "across", "against", "around", "along", "toward", "towards", "another", "every",
            "including", "include", "includes", "according", "reported", "report", "reports", "described", "describes", "noted", "said", "says",
            "claim", "claims", "finding", "findings", "page", "pages", "article", "paper", "papers", "book", "books", "study", "studies", "results", "result",
            "using", "used", "based", "found", "shows", "show", "shown", "measured", "measure", "measures", "measurement", "observed", "observe",
            "increased", "decreased", "raised", "reduced", "reduces", "lowers", "changes", "changed", "involves"));
    static final Set<String> ACADEMIC = new HashSet<>(List.of(
            // sublist 1
            "analys", "analyz", "approach", "area", "assess", "assume", "authority", "available", "benefit", "concept", "consist", "constitut", "context",
            "contract", "creat", "data", "defin", "deriv", "distribut", "econom", "environment", "establish", "estimat", "evident", "evidence", "export",
            "factor", "financ", "formula", "function", "identif", "income", "indicat", "individual", "interpret", "involv", "issue", "labour", "labor",
            "legal", "legislat", "major", "method", "occur", "percent", "period", "policy", "policies", "principle", "proceed", "process", "requir",
            "research", "respon", "role", "section", "sector", "significan", "similar", "source", "specific", "structur", "theor", "vari",
            // sublist 2
            "achiev", "acquir", "administrat", "affect", "appropriat", "aspect", "assist", "categor", "chapter", "commission", "communit", "complex",
            "comput", "conclu", "conduct", "consequen", "construct", "consum", "credit", "cultur", "design", "distinct", "element", "equat", "evaluat",
            "featur", "final", "focus", "impact", "injur", "institut", "invest", "item", "journal", "maintain", "normal", "obtain", "participat",
            "perceiv", "percept", "positiv", "potential", "previous", "primar", "purchas", "range", "region", "regulat", "relevan", "resid", "resourc",
            "restrict", "secur", "seek", "select", "site", "strateg", "survey", "text", "tradition", "transfer",
            // sublist 3
            "alternativ", "circumstanc", "comment", "compensat", "component", "consent", "considerabl", "constant", "constrain", "contribut", "conven",
            "coordinat", "core", "corporat", "correspond", "criteri", "deduc", "demonstrat", "document", "dominat", "emphasi", "ensur", "exclu",
            "framework", "fund", "illustrat", "immigrat", "impl", "initial", "instanc", "interact", "justif", "layer", "link", "locat", "maximi",
            "minor", "negat", "outcome", "partner", "philosoph", "physical", "proportion", "publish", "react", "register", "rel", "remov", "scheme",
            "sequenc", "shift", "specif", "sufficien", "task", "technic", "techniqu", "technolog", "valid", "volume"));

    /** Whether a word is a commonplace: a function word, or an Academic Word List headword by stem. */
    static boolean commonplace(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        if (FUNCTION.contains(w)) return true;
        for (String stem : ACADEMIC) if (w.startsWith(stem)) return true;
        return false;
    }

    /** Kept as a set view for the callers that test membership: every function word, and stems answer through commonplace(). */
    static final Set<String> STOP = new java.util.AbstractSet<>() {
        @Override public boolean contains(Object o) { return o instanceof String && commonplace((String) o); }
        @Override public java.util.Iterator<String> iterator() { return FUNCTION.iterator(); }
        @Override public int size() { return FUNCTION.size(); }
    };

    private Bridges() { }

    // ---- areas ----

    /** Every subject with enough findings, as an area. */
    public static List<Area> areas(LibraryStore store) throws IOException {
        Map<String, List<Finding>> bySubject = new LinkedHashMap<>();
        for (Finding f : store.scanFindings().findings()) {
            if (f.state() == Finding.State.retired) continue;
            for (String s : f.subjects()) bySubject.computeIfAbsent(s, k -> new ArrayList<>()).add(f);
        }
        Vocabulary vocab = Files.exists(store.subjectsFile()) ? Vocabulary.read(store.subjectsFile()) : null;
        Graph g = Graph.build(store);
        List<Area> out = new ArrayList<>();
        for (Map.Entry<String, List<Finding>> e : bySubject.entrySet()) {
            if (e.getValue().size() < MIN_FINDINGS) continue;
            Set<String> terms = new HashSet<>(), entities = new LinkedHashSet<>(), named = new LinkedHashSet<>(), sources = new HashSet<>(), head = new HashSet<>();
            for (Finding f : e.getValue()) {
                terms.addAll(words(f.title() + " " + f.body()));
                head.addAll(words(f.title()));   // a claim's title and triple carry its nouns; its body carries the connective tissue
                if (f.triple() != null) { entities.add(f.triple().subject()); entities.add(f.triple().object()); named.add(f.triple().subject()); terms.addAll(words(f.triple().subject() + " " + f.triple().object())); head.addAll(words(f.triple().subject() + " " + f.triple().object())); }
                for (Finding.Source src : f.sources()) sources.add(org.researchzosho.tools.Fetch.canonical(src.locator()));
            }
            Vocabulary.Term t = vocab == null ? null : vocab.get(e.getKey());
            out.add(new Area(e.getKey(), label(e.getKey(), vocab), t == null || t.description() == null ? "" : t.description(), e.getValue(), terms, entities, named, sources, head));
        }
        return out;
    }

    /** The catalogue's description when it is a short name ("Raynaud's disease"), else the slug's last part ("fish oil"). */
    static String label(String slug, Vocabulary vocab) {
        Vocabulary.Term t = vocab == null ? null : vocab.get(slug);
        if (t != null && t.description() != null) {
            String d = t.description().strip().replaceAll("\\s*[;(—–].*$", "");
            if (!d.isEmpty() && d.split("\\s+").length <= 3) return d;
        }
        String last = slug.contains("--") ? slug.substring(slug.lastIndexOf("--") + 2) : slug;
        return last.replace('-', ' ');
    }

    /** Lowercase, apostrophes dropped, other punctuation to spaces: "Raynaud's" and "raynauds" are one name. */
    static String norm(String s) { return (" " + s.toLowerCase(Locale.ROOT).replaceAll("[’']", "").replaceAll("[^\\p{L}\\p{N}]+", " ").strip() + " "); }

    static boolean namesBoth(String text, Area a, Area c) {
        String t = norm(text);
        boolean an = false, cn = false;
        for (String n : a.names()) if (t.contains(n)) an = true;
        for (String n : c.names()) if (t.contains(n)) cn = true;
        return an && cn;
    }

    /** Facet of a subject slug: the part before the first "--", or the slug itself. */
    static String facet(String slug) { return slug.contains("--") ? slug.substring(0, slug.indexOf("--")) : slug; }

    static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}-]{3,}");

    static Set<String> words(String text) {
        Set<String> out = new HashSet<>();
        java.util.regex.Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) { String w = m.group(); if (!STOP.contains(w) && !w.matches("\\d+")) out.add(w); }
        return out;
    }

    /** A plain word that can carry a bridge: five letters or more and not an adverb, a participle or a comparative — those read as connective tissue, not as a thing both areas speak of. Entity words are judged by specificity alone. */
    static boolean contentWord(String w) {
        return w.length() >= WORD_CHARS && !w.endsWith("ly") && !w.endsWith("ing") && !w.endsWith("ed") && !w.endsWith("er") && !w.endsWith("est") && !w.matches(".*\\d.*");
    }

    // ---- the sensor ----

    /** Candidate pairs from a focus area outward (or from every area when {@code focus} is null), ranked; nothing filed. */
    public static List<Pair> candidates(LibraryStore store, Settings s, String focus, Random rnd, Researcher.Tools web) throws IOException {
        return candidates(store, s, focus, rnd, web, Explain.drive());
    }

    /** {@code drive} names the middles when the shelves join nothing and the web is allowed; null leaves that off. */
    public static List<Pair> candidates(LibraryStore store, Settings s, String focus, Random rnd, Researcher.Tools web, Researcher.Drive drive) throws IOException {
        return candidates(store, s, focus, rnd, web, drive, new ArrayList<>());
    }

    /** {@code triedOutside} collects the middles the model named for pairs taken outside the library, backed or not. */
    public static List<Pair> candidates(LibraryStore store, Settings s, String focus, Random rnd, Researcher.Tools web, Researcher.Drive drive, List<String> triedOutside) throws IOException {
        return candidates(store, s, focus, rnd, web, drive, triedOutside, false);
    }

    /** {@code remember}: write the middles searched to the ledger, so later nights do not search them again. Only a real run remembers. */
    public static List<Pair> candidates(LibraryStore store, Settings s, String focus, Random rnd, Researcher.Tools web, Researcher.Drive drive, List<String> triedOutside, boolean remember) throws IOException {
        int[] opened = {0};
        List<Area> areas = areas(store);
        if (areas.size() < 2) return List.of();
        Map<String, Area> bySlug = new HashMap<>(); for (Area a : areas) bySlug.put(a.slug(), a);
        // specificity: how many areas carry each term, and how many findings in all (a typo is in one; a filler word is in a tenth of them)
        Map<String, Integer> df = new HashMap<>(), dfFindings = new HashMap<>();
        for (Area a : areas) for (String t : a.terms()) df.merge(t, 1, Integer::sum);
        List<Finding> every = store.scanFindings().findings();
        for (Finding f : every) for (String t : words(f.title() + " " + f.body() + (f.triple() == null ? "" : " " + f.triple().subject() + " " + f.triple().object()))) dfFindings.merge(t, 1, Integer::sum);
        int maxFindings = Math.max(4, every.size() / 10);
        // how many claims rest on each concept: a concept a tenth of the library names is a commonplace, not a bridge
        Map<String, Integer> conceptDf = new HashMap<>();
        for (Finding f : every) for (String c : Concepts.of(f)) conceptDf.merge(c, 1, Integer::sum);
        // specific = on at most a fifth of the areas; in a small library at most three, or nothing could ever bridge
        int maxDf = Math.max(3, (int) Math.floor(areas.size() * COMMON));
        Graph g = Graph.build(store);
        List<Area> starts = new ArrayList<>();
        if (focus != null && !focus.isBlank()) {
            Area a = bySlug.get(focus);
            if (a == null) for (Area x : areas) if (x.label().equalsIgnoreCase(focus) || x.slug().endsWith("--" + focus)) a = x;
            if (a == null) throw new IOException("no area named " + focus + " with " + MIN_FINDINGS + " findings or more; the areas are: " + String.join(", ", areas.stream().map(Area::slug).toList()));
            starts.add(a);
        } else {
            Map<String, Integer> heat = Heat.load(store);
            areas.sort((x, y) -> Integer.compare(heatOf(y, heat), heatOf(x, heat)));
            starts.addAll(areas);
        }
        Walk walk = s.graph() ? new Walk(g, Embeddings.configured()) : null;
        Area target = s.toward().isEmpty() ? null : bySlug.get(s.toward());
        if (target == null && !s.toward().isEmpty()) for (Area x : areas) if (x.label().equalsIgnoreCase(s.toward()) || x.slug().endsWith("--" + s.toward())) target = x;
        List<Pair> out = new ArrayList<>();
        Set<String> seenPairs = new HashSet<>();
        List<Finding> allFindings = null; List<String[]> allRaw = null;
        for (Area a : starts) {
            for (Area c : areas) {
                if (c == a) continue;
                String key = a.slug().compareTo(c.slug()) < 0 ? a.slug() + "|" + c.slug() : c.slug() + "|" + a.slug();
                if (!seenPairs.add(key)) continue;
                if (!s.away().isEmpty() && (matchesAny(c.slug() + " " + c.label(), s.away()) || matchesAny(a.slug() + " " + a.label(), s.away()))) continue;
                if (!s.since().isEmpty() && c.findings().stream().noneMatch(f -> f.validAsOf().compareTo(s.since()) >= 0)) continue;
                int hops = hops(g, "subject:" + a.slug(), "subject:" + c.slug());
                // low = subjects sharing a parent facet (the note's "neighbours"); hops on the map do not count here, since
                // two areas whose findings name the same entity are two hops apart — the bridge itself would make them near
                boolean siblings = facet(a.slug()).equals(facet(c.slug()));
                boolean random = false;
                switch (s.reach()) {
                    case "low" -> { if (!siblings) continue; }
                    case "medium" -> { if (!siblings && !(hops >= 0 && hops <= 4)) { if (rnd.nextDouble() < 0.10) random = true; else continue; } }
                    default -> { if (rnd.nextDouble() < 0.33) random = true; }
                }
                if (target != null) hops = hops(g, "subject:" + c.slug(), "subject:" + target.slug());
                // the bridge terms: in both, specific, and not the areas' own names
                List<String> via = new ArrayList<>();
                Set<String> own = new HashSet<>(a.labelWords()); own.addAll(c.labelWords());
                Set<String> entityWords = new HashSet<>(); for (String en : a.entities()) if (c.entities().stream().anyMatch(x -> !words(x).isEmpty() && !Collections.disjoint(words(x), words(en)))) { entityWords.addAll(words(en)); }
                for (String t : a.terms()) {
                    if (!c.terms().contains(t) || own.contains(t)) continue;
                    if (df.getOrDefault(t, 0) > maxDf || dfFindings.getOrDefault(t, 0) > maxFindings) continue;
                    if (!entityWords.contains(t) && !contentWord(t)) continue;
                    // a bridge is something both areas' claims are ABOUT: the word stands in a title or a triple on both sides
                    if (!a.headTerms().contains(t) || !c.headTerms().contains(t)) continue;
                    via.add(t);
                }
                // entities both name count double: they are the graph's own words
                double score = 0;
                for (String t : via) score += (a.entities().stream().anyMatch(e -> words(e).contains(t)) && c.entities().stream().anyMatch(e -> words(e).contains(t)) ? 2.0 : 1.0) * Math.log(1.0 + (double) areas.size() / df.get(t));
                if (!s.terms()) via.clear();
                // the graph: paths from what A's claims are about to what C's are about, through concepts in between
                List<String> paths = walk == null ? List.of() : walk.paths(a, c, s.strict() ? 2 : 3);
                Set<String> middles = new LinkedHashSet<>();
                for (String path : paths) for (String m : Walk.middlesOf(path)) middles.add(m);
                boolean termsEnough = via.size() >= (s.strict() ? STRICT_TERMS : LOOSE_TERMS);
                // strict wants a middle both sides rest on that is not a commonplace — one is enough when it is specific
                // (microbial bone diagenesis and microbially influenced corrosion meet at exactly one concept, 2026-09-15)
                boolean specificMiddle = middles.stream().anyMatch(m -> conceptDf.getOrDefault(m, 0) <= maxFindings);
                boolean graphEnough = s.strict() ? specificMiddle : !paths.isEmpty();
                List<Join> joins = List.of();
                if (!termsEnough && !graphEnough && s.sources().contains("web") && opened[0] < OPEN_PAIRS) {
                    // the shelves do not join these two: let the middle come from outside, and make the web back it on both sides
                    opened[0]++;
                    joins = openJoins(store, a, c, drive, web, triedOutside, remember);
                }
                if (!termsEnough && !graphEnough && joins.isEmpty()) continue;
                if (!termsEnough) via.clear();   // the terms alone did not carry it; the paths do
                for (Join j : joins) { via.add("⇢" + j.middle()); score += 1.5; }
                for (String m : middles) if (!via.contains("→" + m)) via.add("→" + m);
                for (String path : paths) score += 2.0 / Math.max(1, Walk.hopsOf(path) - 1);
                // novelty: no claim joins them (loose); no source read names both (strict); on the web (when allowed)
                int co = 0;
                if (s.strict()) {
                    if (allFindings == null) { allFindings = every; allRaw = rawTexts(store); }
                    Set<String> shared = new HashSet<>(a.sources()); shared.retainAll(c.sources());
                    co = shared.size() + coMentions(a, c, allFindings, allRaw);
                    if (co == 0 && s.sources().contains("web") && web != null) co += webCoMentions(a, c, web);
                    if (co > 0) { out.add(new Pair(a, c, via, score, hops, co, random, paths, joins)); continue; }   // kept in the list, marked, never proposed
                } else if (jointClaim(a, c)) continue;
                via.sort((x, y) -> Integer.compare(df.getOrDefault(x, Integer.MAX_VALUE), df.getOrDefault(y, Integer.MAX_VALUE)));
                out.add(new Pair(a, c, via, score, hops, 0, random, paths, joins));
            }
            if (focus == null && out.size() >= TOP * 4) break;   // enough from the hottest areas
        }
        final Area tgt = target;
        out.sort((x, y) -> {
            if ((x.coMentions() == 0) != (y.coMentions() == 0)) return x.coMentions() == 0 ? -1 : 1;
            if (tgt != null && x.hops() != y.hops()) return Integer.compare(x.hops() < 0 ? 99 : x.hops(), y.hops() < 0 ? 99 : y.hops());
            return Double.compare(y.score(), x.score());
        });
        return out;
    }

    /**
     * The graph sensor: shortest paths between the entities of two areas through concept nodes in between. Subject
     * nodes are never intermediates (everything filed under a subject meets there). Nodes whose labels embed alike
     * (cosine ≥ FOLD) are walked as one node, so "blood viscosity" and "viscosity of blood" meet even without an alias.
     */
    static final class Walk {
        static final double FOLD = Double.parseDouble(org.researchzosho.Config.get("RESEARCHZOSHO_BRIDGES_FOLD", "0.92"));
        static final int MAX_PATHS = 6;
        final Graph g;
        final Map<String, String> fold = new HashMap<>();          // node id → representative id
        final Map<String, List<String>> adj = new HashMap<>();     // representative → neighbours (representatives)
        final Map<String, Map<String, Graph.Edge>> edgeOf = new HashMap<>();
        Walk(Graph g, Embeddings.Embedder embedder) {
            this.g = g;
            foldNodes(embedder);
            for (Graph.Edge e : g.edges()) {
                String a = rep(e.from()), b = rep(e.to());
                if (a.equals(b)) continue;
                adj.computeIfAbsent(a, k -> new ArrayList<>()).add(b); adj.computeIfAbsent(b, k -> new ArrayList<>()).add(a);
                edgeOf.computeIfAbsent(a, k -> new HashMap<>()).putIfAbsent(b, e); edgeOf.computeIfAbsent(b, k -> new HashMap<>()).putIfAbsent(a, e);
            }
        }
        String rep(String id) { return fold.getOrDefault(id, id); }
        private void foldNodes(Embeddings.Embedder embedder) {
            if (embedder == null || "none".equals(embedder.modelId())) return;
            List<Graph.Node> ns = new ArrayList<>(); for (Graph.Node n : g.nodes()) if (!n.id().startsWith("subject:")) ns.add(n);
            if (ns.size() < 2 || ns.size() > 4000) return;
            List<float[]> vecs;
            try { vecs = embedder.embedAll(ns.stream().map(Graph.Node::label).toList()); } catch (Exception e) { return; }
            if (vecs == null || vecs.size() != ns.size()) return;
            for (int i = 0; i < ns.size(); i++) {
                if (vecs.get(i) == null || fold.containsKey(ns.get(i).id())) continue;
                for (int j = i + 1; j < ns.size(); j++) {
                    if (vecs.get(j) == null || fold.containsKey(ns.get(j).id())) continue;
                    if (cosine(vecs.get(i), vecs.get(j)) >= FOLD) fold.put(ns.get(j).id(), ns.get(i).id());
                }
            }
        }
        static double cosine(float[] a, float[] b) {
            double dot = 0, na = 0, nb = 0;
            for (int i = 0; i < Math.min(a.length, b.length); i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
            return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
        }
        /** Paths of at most {@code maxHops} edges from any entity of A to any entity of C with at least one node between, rendered. */
        List<String> paths(Area a, Area c, int maxHops) {
            // from what A's claims are ABOUT (their triple subjects) to what C's are about; the objects of either side, and
            // anything else on the map, are the concepts in between. Fish oil →lowers→ blood viscosity ←involves← Raynaud's.
            Set<String> starts = new LinkedHashSet<>(), ends = new LinkedHashSet<>();
            for (String en : a.subjectsNamed()) starts.add(rep(g.nodeIdOf(en)));
            for (String en : c.subjectsNamed()) ends.add(rep(g.nodeIdOf(en)));
            // an area itself is an end of the walk, never a step through it: what its claims without a triple rest on
            // hangs on the subject node, and that knowledge is the area's as much as any claim's
            starts.add(rep("subject:" + a.slug()));
            ends.add(rep("subject:" + c.slug()));
            starts.removeAll(ends);
            List<String> out = new ArrayList<>();
            Set<String> seenMiddles = new HashSet<>();
            for (String s0 : starts) {
                Map<String, String> prev = new HashMap<>(); Map<String, Integer> dist = new HashMap<>();
                ArrayDeque<String> q = new ArrayDeque<>(); dist.put(s0, 0); q.add(s0);
                while (!q.isEmpty()) {
                    String cur = q.poll(); int d = dist.get(cur);
                    if (d >= maxHops) continue;
                    for (String n : adj.getOrDefault(cur, List.of())) {
                        // an area's own node is a fine END — what its claims without a triple rest on hangs there — but never
                        // a step through: every claim under a subject meets at it, so routing through one joins anything to anything
                        if (n.startsWith("subject:") && !ends.contains(n)) continue;
                        // a concept that is only how a field talks about itself ("consensus", "criteria") is no bridge
                        if (!ends.contains(n) && STOP.contains(labelOf(n).toLowerCase(Locale.ROOT).strip())) continue;
                        if (ends.contains(n)) {
                            // an end is never "visited": every distinct middle that reaches it is its own path
                            if (d + 1 < 2) continue;   // a direct edge = a claim already joining them
                            List<String> chain = new ArrayList<>(); chain.add(n); for (String x = cur; x != null; x = prev.get(x)) chain.add(0, x);
                            String middle = String.join("|", chain.subList(1, chain.size() - 1));
                            if (seenMiddles.add(middle)) out.add(render(chain));
                            if (out.size() >= MAX_PATHS) return out;
                            continue;
                        }
                        if (dist.containsKey(n)) continue;
                        dist.put(n, d + 1); prev.put(n, cur);
                        q.add(n);
                    }
                }
            }
            return out;
        }
        String render(List<String> chain) {
            StringBuilder sb = new StringBuilder(labelOf(chain.get(0)));
            for (int i = 1; i < chain.size(); i++) {
                Graph.Edge e = edgeOf.getOrDefault(chain.get(i - 1), Map.of()).get(chain.get(i));
                boolean forward = e != null && rep(e.from()).equals(chain.get(i - 1));
                sb.append(forward ? " →" : " ←").append(e == null ? "" : e.predicate().replace('-', ' ')).append(forward ? "→ " : "← ").append(labelOf(chain.get(i)));
            }
            return sb.toString();
        }
        String labelOf(String id) { Graph.Node n = g.node(id); return n == null ? id : n.label(); }
        static List<String> middlesOf(String path) {
            String[] parts = path.split(" [→←][^→←]*[→←] ");
            return parts.length <= 2 ? List.of() : List.of(parts).subList(1, parts.length - 1);
        }
        static int hopsOf(String path) { return path.split(" [→←][^→←]*[→←] ").length - 1; }
    }

    /** Middles the model may name for one pair, and pairs of areas one run may take outside the library. */
    static final int OPEN_MIDDLES = org.researchzosho.Config.getInt("RESEARCHZOSHO_BRIDGES_OPEN_MIDDLES", 10);
    static final int OPEN_PAIRS = org.researchzosho.Config.getInt("RESEARCHZOSHO_BRIDGES_OPEN_PAIRS", 3);

    /** A few of an area's claim titles: what it has established, without handing the model its vocabulary to copy back. */
    static String titles(Area a) {
        StringBuilder sb = new StringBuilder();
        for (Finding f : a.findings().subList(0, Math.min(6, a.findings().size()))) sb.append("  - ").append(Acquisitions.compress(f.title(), 90)).append('\n');
        return sb.toString();
    }

    /** Everything an area rests on: the concepts its claims name, and the entities its triples name. */
    static List<String> restsOn(Area a) {
        List<String> out = new ArrayList<>();
        for (Finding f : a.findings()) for (String c : Concepts.of(f)) if (!out.contains(c)) out.add(c);
        for (String e : a.entities()) if (!out.contains(e)) out.add(e);
        return out;
    }

    /**
     * The middles the model can think of for a pair the shelves do not join. It names them; it may not assert them —
     * every one is thrown away unless sources on the web carry it together with EACH area.
     */
    static List<Join> openJoins(Area a, Area c, Researcher.Drive drive, Researcher.Tools web) { return openJoins(null, a, c, drive, web, new ArrayList<>()); }

    /** {@code tried} collects every middle the model named, backed or not, so a dry run can show what was looked for. {@code store} may be null (no ledger). */
    static List<Join> openJoins(LibraryStore store, Area a, Area c, Researcher.Drive drive, Researcher.Tools web, List<String> tried) { return openJoins(store, a, c, drive, web, tried, false); }

    static List<Join> openJoins(LibraryStore store, Area a, Area c, Researcher.Drive drive, Researcher.Tools web, List<String> tried, boolean remember) {
        if (drive == null || web == null) return List.of();
        Set<String> already = store == null ? Set.of() : triedBefore(store, a, c);
        // the library's own evidence first: where the two areas' concepts come closest by the embedder. The model sees it
        // and may add to it; it does not nominate alone where the map has already measured (2026-09-15).
        List<Near> near = nearestConcepts(a, c, Embeddings.configured(), 8);
        List<String> fromMap = mapMiddles(near);
        List<String> middles = new ArrayList<>(fromMap);
        StringBuilder closest = new StringBuilder();
        if (near != null) for (Near x : near.subList(0, Math.min(NEAR_CONCEPTS, near.size()))) closest.append("  - ").append(x.a()).append("  ~  ").append(x.c()).append('\n');
        try {
            ArrayNode messages = M.createArrayNode();
            messages.addObject().put("role", "system").put("content",
                    "Name up to " + OPEN_MIDDLES + " things that act on, limit or shape the work in BOTH of the two areas below.\n"
                    + "Go through these kinds in order and take one or two from each, so the answers are spread across them:\n"
                    + "  1. something physical or chemical acting on each\n"
                    + "  2. a material, an instrument, or a way of measuring\n"
                    + "  3. a capacity or limit of the people doing the work\n"
                    + "  4. how the work is organised: its economics, its rules, its institutions, its customs\n"
                    + "  5. a living agent, an environment, a period of history, or a mathematical structure\n"
                    + "Name each thing in one or two ordinary words, the way it would be written about EACH area separately by someone studying it. "
                    + "Prefer things that belong to neither area alone, and mean the same thing in both. "
                    + "One per line, lowercase, no explanation, no numbering.");
            messages.addObject().put("role", "user").put("content", Fence.open("AREAS") + "\nAREA A: " + a.label() + "\nWhat it has established:\n"
                    + titles(a) + "\nAREA C: " + c.label() + "\nWhat it has established:\n" + titles(c)
                    + (closest.length() == 0 ? "" : "\nWhere the library's own map finds the two closest (a concept of A  ~  a concept of C):\n" + closest)
                    + Fence.close("AREAS"));
            String reply = drive.classify(messages, 180);
            if (reply == null) return List.of();
            for (String line : reply.split("\n")) {
                if (middles.size() >= fromMap.size() + OPEN_MIDDLES) break;
                String m = line.strip().replaceAll("^[-*•\\d.)\\s]+", "").replaceAll("[.;:]+$", "").toLowerCase(Locale.ROOT).strip();
                if (m.isEmpty() || m.equalsIgnoreCase("none") || m.length() > 60 || m.split("\\s+").length > 4) continue;
                if (STOP.contains(m) || middles.contains(m)) continue;
                middles.add(m);
            }
        } catch (Exception e) { return List.of(); }
        List<Join> out = new ArrayList<>();
        List<String> searched = new ArrayList<>();
        for (String m : middles) {
            if (already.contains(m)) { tried.add(m + " — searched within the last " + TRIED_DAYS + " days"); continue; }
            searched.add(m);
            String ea = backing(web, m, a), ec = backing(web, m, c);
            if (ea.isEmpty() || ec.isEmpty()) { tried.add(m + " — " + (ea.isEmpty() && ec.isEmpty() ? "nothing found for either" : ea.isEmpty() ? "nothing found with " + a.label() : "nothing found with " + c.label())); continue; }
            tried.add(m + (fromMap.contains(m) ? " (from the map)" : "") + " — backed on both");
            out.add(new Join(m, ea, ec));
            if (out.size() >= 3) break;
        }
        if (store != null && remember) recordTried(store, a, c, searched);
        // the most coherent middle leads: the one whose two sources read most alike. A word that means two things in the
        // two areas has two sources that read unalike — it is still proposed (the person judges), but not first.
        return coherent(out);
    }

    /** Backed middles ordered by the likeness of their two sources, by the embedder; unchanged when there is no embedder. */
    static List<Join> coherent(List<Join> joins) {
        if (joins.size() < 2) return joins;
        Embeddings.Embedder e = Embeddings.configured();
        if (e == null || "none".equals(e.modelId())) return joins;
        try {
            List<String> texts = new ArrayList<>();
            for (Join j : joins) { texts.add(j.evidenceA()); texts.add(j.evidenceC()); }
            List<float[]> v = e.embedAll(texts);
            if (v == null || v.size() != texts.size()) return joins;
            Map<Join, Double> score = new HashMap<>();
            for (int i = 0; i < joins.size(); i++) score.put(joins.get(i), v.get(2 * i) == null || v.get(2 * i + 1) == null ? 0.0 : Walk.cosine(v.get(2 * i), v.get(2 * i + 1)));
            List<Join> sorted = new ArrayList<>(joins);
            sorted.sort((x, y) -> Double.compare(score.get(y), score.get(x)));
            return sorted;
        } catch (Exception ex) { return joins; }
    }

    /**
     * The RESULTS of a search, one string per entry (its title and snippet). The tool's first line echoes the query,
     * and a query naming two things therefore "names both" by construction — every middle passed, and every pair looked
     * already-joined, until this read the entries instead of the lines (2026-09-15).
     */
    static List<String> searchEntries(String out) {
        List<String> flat = new ArrayList<>();
        for (String[] e : searchResults(out)) flat.add(e[1]);
        return flat;
    }

    /** The url in a search result entry, or "". */
    static String urlOf(String entry) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("https?://\\S+").matcher(entry == null ? "" : entry);
        return m.find() ? m.group() : "";
    }

    /** Each result as {title, title + snippet}. */
    static List<String[]> searchResults(String out) {
        List<String[]> entries = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : (out == null ? "" : out).split("\n")) {
            if (line.startsWith("<<<") || line.startsWith("results for ") || line.startsWith("no results for")) {
                if (cur.length() > 0) { entries.add(new String[]{titles.get(titles.size() - 1), cur.toString()}); cur.setLength(0); }
                continue;
            }
            if (line.matches("^\\d+\\. .*")) {
                if (cur.length() > 0) entries.add(new String[]{titles.get(titles.size() - 1), cur.toString()});
                cur.setLength(0);
                String title = line.replaceFirst("^\\d+\\. ", "").strip();
                titles.add(title);
                cur.append(title);
            } else if (line.startsWith("   ") && cur.length() > 0) cur.append(' ').append(line.strip());
        }
        if (cur.length() > 0) entries.add(new String[]{titles.get(titles.size() - 1), cur.toString()});
        return entries;
    }

    /**
     * The words an area is about, by one rule for every area: the shelf's label, plus the specific content words that
     * stand in its claims' titles and triples, most-used first. No word is chosen by hand and none is chosen for a pair;
     * a source counts as being about the area when it uses any of them. Earlier versions searched under a single
     * guessed word (the longest label word, then the most-used title word) and each guess fit the pair it was made on.
     */
    static final int VOCAB_TERMS = 40;

    static List<String> areaVocabulary(Area area) {
        List<String> out = new ArrayList<>();
        for (String w : area.label().toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (w.length() >= 4 && !STOP.contains(w) && !out.contains(w)) out.add(w);
        Map<String, Integer> count = new LinkedHashMap<>();
        for (Finding f : area.findings()) {
            Set<String> once = new LinkedHashSet<>();
            // what the claim is about (title, triple) and what it rests on (its concepts): all of it is the area's own vocabulary
            String text = f.title() + " " + (f.triple() == null ? "" : f.triple().subject() + " " + f.triple().object()) + " " + String.join(" ", Concepts.of(f));
            for (String w : words(text)) if ((contentWord(w) || w.length() >= 5) && !w.matches(".*\\d.*")) once.add(w);   // "1980s" is not a word an area is about
            for (String w : once) count.merge(w, 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> byCount = new ArrayList<>(count.entrySet());
        byCount.sort((x, y) -> y.getValue() != x.getValue() ? y.getValue() - x.getValue() : x.getKey().compareTo(y.getKey()));
        for (var e : byCount) { if (!out.contains(e.getKey())) out.add(e.getKey()); if (out.size() >= VOCAB_TERMS) break; }
        return out;
    }

    /** Whether a text uses any of the area's words (whole words), or one of its names. */
    static boolean aboutArea(String text, Area area, List<String> vocab) {
        String t = norm(text);
        for (String w : vocab) if (t.contains(" " + w + " ")) return true;
        for (String n : area.names()) if (t.contains(n)) return true;
        return false;
    }

    /**
     * A crude stem: enough that "magnetic" matches "magnetism" and "random" matches "randomness". A trailing e goes
     * too, so "sample" and "sampling" stem alike ("sampl") — the suffix rule alone left "sample" whole and cut
     * "sampling" to "sampl", and the two never met (2026-09-15).
     */
    static String stem(String w) {
        String x = w.toLowerCase(Locale.ROOT);
        for (String suf : new String[]{"ness", "ities", "ity", "ical", "ism", "ies", "ing", "ed", "es", "s"}) if (x.length() - suf.length() >= 4 && x.endsWith(suf)) { x = x.substring(0, x.length() - suf.length()); break; }
        if (x.length() >= 5 && x.endsWith("e")) x = x.substring(0, x.length() - 1);
        return x;
    }

    /** Two words share a stem when either stem begins the other and the shorter is at least four letters long. */
    static boolean sameStem(String a, String b) {
        String x = stem(a), y = stem(b);
        if (Math.min(x.length(), y.length()) < 4) return x.equals(y);
        return x.startsWith(y) || y.startsWith(x);
    }

    /**
     * Whether a search result is ABOUT the middle rather than mentioning it in passing: a content word of the middle
     * stands in the title (by stem — the papers on the avian magnetic compass are about magnetic fields though no title
     * says the phrase), or the middle is named more than once across title and snippet. A rule for every middle; the
     * earlier test wanted the exact phrase in the title, which failed those papers and passed a journal named Entropy.
     */
    static boolean aboutMiddle(String title, String entry, String middle) {
        String t = norm(title);
        for (String w : words(middle)) {
            if (w.length() < 4 || commonplace(w)) continue;
            for (String tw : t.strip().split(" ")) if (sameStem(tw, w)) return true;
        }
        String e = norm(entry), m = norm(middle).strip();
        int n = 0, i = 0;
        while ((i = e.indexOf(m, i)) >= 0) { n++; i += m.length(); }
        return n >= 2;
    }

    /**
     * One web search: the middle exactly, the area by any of its own words. The first RESULT that is about the middle,
     * about the area (uses one of its words), and from a source the library would take — or "". The model never sees
     * an unbacked middle.
     */
    static String backing(Researcher.Tools web, String middle, Area area) {
        try {
            List<String> vocab = areaVocabulary(area);
            for (org.researchzosho.tools.Tool t : web.web("bridges")) {
                if (!t.name().equals("web_search")) continue;
                // the middle quoted, the area by its label words unquoted: what a person types. A six-way OR of the area's
                // vocabulary made the engine return the area's own literature with the middle nowhere in it (2026-09-15).
                String out = t.execute(M.createObjectNode().put("query", "\"" + middle + "\" " + area.label()));
                for (String[] r : searchResults(out)) {
                    if (!aboutMiddle(r[0], r[1], middle)) continue;             // about the middle, not a passing mention
                    if (!SourceTier.of(urlOf(r[1])).autoPromotes()) continue;   // from a source this library would take
                    if (aboutArea(r[1], area, vocab)) return r[1].length() > 300 ? r[1].substring(0, 300) : r[1];
                }
                return "";
            }
        } catch (Exception ignored) { }
        return "";
    }

    static int heatOf(Area a, Map<String, Integer> heat) { int n = 0; for (Finding f : a.findings()) n += heat.getOrDefault(f.id(), 0); return n; }

    static boolean matchesAny(String text, List<String> words) { String t = text.toLowerCase(Locale.ROOT); for (String w : words) if (!w.isBlank() && t.contains(w.strip())) return true; return false; }

    /** Shortest path on the map between two node ids, or -1. */
    static int hops(Graph g, String from, String to) {
        if (g.node(from) == null || g.node(to) == null) return -1;
        Map<String, List<String>> adj = new HashMap<>();
        for (Graph.Edge e : g.edges()) { adj.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to()); adj.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e.from()); }
        Map<String, Integer> dist = new HashMap<>(); ArrayDeque<String> q = new ArrayDeque<>();
        dist.put(from, 0); q.add(from);
        while (!q.isEmpty()) {
            String cur = q.poll();
            if (cur.equals(to)) return dist.get(cur);
            for (String n : adj.getOrDefault(cur, List.of())) if (!dist.containsKey(n)) { dist.put(n, dist.get(cur) + 1); q.add(n); }
        }
        return -1;
    }

    static List<String[]> rawTexts(LibraryStore store) {
        List<String[]> out = new ArrayList<>();
        try {
            if (!Files.isDirectory(store.rawDir())) return out;
            try (var l = Files.list(store.rawDir())) {
                for (Path p : l.filter(x -> x.toString().endsWith(".md")).toList()) {
                    try { String[] r = RawCapture.read(p); out.add(new String[]{p.getFileName().toString(), (r[1] + "\n" + r[2]).toLowerCase(Locale.ROOT)}); } catch (Exception ignored) { }
                }
            }
        } catch (IOException ignored) { }
        return out;
    }

    /** Sources read (findings and raw captures) whose text names both areas. */
    static int coMentions(Area a, Area c, List<Finding> findings, List<String[]> raw) {
        int n = 0;
        for (Finding f : findings) if (namesBoth(f.title() + "\n" + f.body(), a, c)) n++;
        for (String[] r : raw) if (namesBoth(r[1], a, c)) n++;
        return n;
    }

    /** A claim already filed under both subjects. */
    static boolean jointClaim(Area a, Area c) {
        for (Finding f : a.findings()) if (f.subjects().contains(c.slug())) return true;
        return false;
    }

    /** One web search for both names; results whose title or snippet carry both count. */
    static int webCoMentions(Area a, Area c, Researcher.Tools web) {
        try {
            for (org.researchzosho.tools.Tool t : web.web("bridges")) {
                if (!t.name().equals("web_search")) continue;
                String out = t.execute(M.createObjectNode().put("query", "\"" + a.label() + "\" \"" + c.label() + "\""));
                int n = 0;
                for (String entry : searchEntries(out)) if (namesBoth(entry, a, c)) n++;   // the entries, never the query echo
                return n;
            }
        } catch (Exception ignored) { }
        return 0;
    }

    // ---- the model's question ----

    /** The question for a pair: the model's when there is one, a plain template otherwise. Never a claim. */
    public static String question(Researcher.Drive drive, Pair p) {
        String via = String.join(", ", p.via().subList(0, Math.min(4, p.via().size())).stream().map(v -> v.startsWith("→") ? v.substring(1) : v).toList());
        String fallback = !p.joins().isEmpty()
                ? "Does " + p.joins().get(0).middle() + " connect " + p.a().label() + " and " + p.c().label() + "? Sources outside this library carry it with each of them, and nothing here names the two together."
                : "Does what the library holds on " + p.a().label() + " bear on " + p.c().label() + "? " + (p.paths().isEmpty() ? "Both speak of " + via + ", and no source read here names the two together." : "The map joins them: " + p.paths().get(0) + "; no source read here names the two together.");
        if (drive == null) return fallback;
        try {
            StringBuilder u = new StringBuilder();
            u.append("AREA A: ").append(p.a().label()).append("\n");
            for (Finding f : p.a().findings().subList(0, Math.min(3, p.a().findings().size()))) u.append("- ").append(Acquisitions.compress(f.body().strip(), 300)).append('\n');
            u.append("\nAREA C: ").append(p.c().label()).append("\n");
            for (Finding f : p.c().findings().subList(0, Math.min(3, p.c().findings().size()))) u.append("- ").append(Acquisitions.compress(f.body().strip(), 300)).append('\n');
            List<String> plainTerms = p.via().stream().filter(v -> !v.startsWith("→")).toList();
            if (!plainTerms.isEmpty()) u.append("\nTERMS BOTH AREAS USE, WHICH NO SOURCE READ HERE USES OF BOTH TOGETHER: ").append(String.join(", ", plainTerms.subList(0, Math.min(4, plainTerms.size())))).append('\n');
            if (!p.paths().isEmpty()) { u.append("\nPATHS ON THE LIBRARY'S MAP FROM A TO C (each arrow is a claim on the shelves):\n"); for (String path : p.paths().subList(0, Math.min(4, p.paths().size()))) u.append("- ").append(path).append('\n'); }
            if (!p.joins().isEmpty()) u.append("\nWrite the question about the FIRST middle below; the others are context.\n");
            for (Join j : p.joins()) u.append("\nA MIDDLE THE SHELVES DO NOT HOLD, \"").append(j.middle()).append("\", which sources outside the library carry with each area:\n  with ")
                    .append(p.a().label()).append(": ").append(j.evidenceA()).append("\n  with ").append(p.c().label()).append(": ").append(j.evidenceC()).append('\n');
            ArrayNode messages = M.createArrayNode();
            messages.addObject().put("role", "system").put("content", "You write one research question, at most 40 words, that asks whether a specific mechanism, method or finding in area A also bears on area C, through the shared terms. "
                    + "A question, never a claim: it must be answerable by reading sources, and it must not assert that the link exists. Name both areas and the strongest shared term. "
                    + "Write the question whenever a research run could test it, even when the transfer is a long shot — the person decides what to keep, and a question nobody asked is the point. "
                    + "When the shared term means two different things in the two areas, ask whether the two meanings share anything after all. Write the question only.");
            messages.addObject().put("role", "user").put("content", Fence.open("PAIR") + "\n" + u + Fence.close("PAIR"));
            String q = drive.classify(messages, 120);
            if (q == null) return fallback;
            q = q.strip().replaceAll("^[\"“]|[\"”]$", "").replaceAll("\\s+", " ");
            // the model phrases; it does not judge. A refusal, a claim instead of a question, or noise all become the plain
            // question — the person decides at the inbox. A veto here swallowed every cross-field pair for a day (2026-09-15).
            if (q.length() < 20 || q.length() > 400 || !q.contains("?") || q.toUpperCase(Locale.ROOT).startsWith("NONE")) return fallback;
            return q;
        } catch (Exception e) { return fallback; }
    }

    // ---- proposals, the ledger, the fate ----

    static Path ledger(LibraryStore store) { return store.root().resolve("catalog").resolve("bridges.jsonl"); }

    /** The frontier line's kind for a proposal: the settings that found it ride along, so the kept ones say what works. */
    static String kind(Proposal p) {
        return "bridge " + p.by() + " " + p.settings().line() + " sensor=" + p.pair().sensor() + " a=" + p.pair().a().slug() + " c=" + p.pair().c().slug() + " via=" + String.join(",", p.pair().via().subList(0, Math.min(3, p.pair().via().size()))).replace(" ", "_") + (p.pair().random() ? " random" : "");
    }

    /** What a proposal rests on, in words the person and the run can use: the paths read on the map, and each outside middle with its two sources. */
    static String evidence(Pair p) {
        StringBuilder sb = new StringBuilder();
        for (String path : p.paths().subList(0, Math.min(3, p.paths().size()))) sb.append("path: ").append(path).append('\n');
        for (Join j : p.joins()) sb.append("middle: ").append(j.middle()).append("\n  with ").append(p.a().label()).append(": ").append(j.evidenceA()).append("\n  with ").append(p.c().label()).append(": ").append(j.evidenceC()).append('\n');
        return sb.toString().strip();
    }

    /** The evidence recorded for a proposal, by its question: from the ledger, "" when there is none. */
    public static String evidenceFor(LibraryStore store, String question) {
        try {
            if (!Files.exists(ledger(store))) return "";
            String found = "";
            for (String line : Files.readAllLines(ledger(store), StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                JsonNode j = M.readTree(line);
                if ("proposed".equals(j.path("event").asText()) && Frontier.sameQuestion(j.path("question").asText(), question)) found = j.path("evidence").asText("");
            }
            return found;
        } catch (IOException e) { return ""; }
    }

    /** Run once: the sensor, the model on the top pairs, up to {@code perNight} proposals filed (none when {@code dry}). */
    public static ObjectNode propose(LibraryStore store, Researcher.Drive drive, Researcher.Tools web, Settings s, String focus, boolean dry, String by, Random rnd) throws IOException {
        ObjectNode r = M.createObjectNode();
        List<String> triedOutside = new ArrayList<>();
        List<Pair> all = candidates(store, s, focus, rnd, s.sources().contains("web") ? web : null, drive, triedOutside, !dry);
        List<Pair> novel = all.stream().filter(p -> p.coMentions() == 0).toList();
        r.put("areas", areas(store).size()); r.put("candidates", all.size()); r.put("novel", novel.size()); r.put("settings", s.line()); r.put("dry", dry);
        if (focus != null) r.put("focus", focus);
        List<Frontier.Line> open = Frontier.read(store);
        ArrayNode props = r.putArray("proposals");
        int filed = 0;
        for (Pair p : novel.subList(0, Math.min(TOP, novel.size()))) {
            if (filed >= s.perNight()) break;
            // one proposal per pair, ever: the ledger remembers
            if (proposedBefore(store, p)) continue;
            String q = question(drive, p);
            Proposal prop = new Proposal(q, p, s, by);
            ObjectNode o = props.addObject();
            o.put("a", p.a().slug()); o.put("c", p.c().slug()); o.put("a_label", p.a().label()); o.put("c_label", p.c().label());
            ArrayNode v = o.putArray("via"); p.via().subList(0, Math.min(6, p.via().size())).forEach(v::add);
            o.put("score", Math.round(p.score() * 100) / 100.0); o.put("hops", p.hops()); o.put("random", p.random()); o.put("sensor", p.sensor()); o.put("question", q);
            ArrayNode ps = o.putArray("paths"); p.paths().forEach(ps::add);
            ArrayNode js = o.putArray("from_outside");
            for (Join j : p.joins()) js.addObject().put("middle", j.middle()).put("with_" + "a", j.evidenceA()).put("with_c", j.evidenceC());
            if (dry) continue;
            boolean dup = false;
            for (Frontier.Line l : open) if (Frontier.sameQuestion(l.text(), q)) { dup = true; break; }
            if (dup) continue;
            store.frontier(kind(prop), q);
            record(store, prop, "proposed", null);
            o.put("filed", true); filed++;
        }
        ArrayNode to = r.putArray("tried_outside"); triedOutside.forEach(to::add);
        ArrayNode skipped = r.putArray("not_novel");
        for (Pair p : all) if (p.coMentions() > 0 && skipped.size() < 5) skipped.addObject().put("a", p.a().slug()).put("c", p.c().slug()).put("sources_naming_both", p.coMentions());
        r.put("filed", filed);
        r.put("summary", (focus == null ? "from the hottest areas" : "from " + focus) + ": " + all.size() + " candidate pair(s), " + novel.size() + " novel, " + (dry ? props.size() + " shown, nothing filed" : filed + " proposal(s) filed as open questions of type bridge") + " (" + s.line() + ")");
        store.circulate("bridges", by + " :: " + r.path("summary").asText());
        return r;
    }

    /**
     * Already proposed: the same pair through the same middles. A pair may be proposed again through a different middle
     * — a homonym proposed one night must not block the real join found the next (2026-09-15).
     */
    static boolean proposedBefore(LibraryStore store, Pair p) {
        try {
            if (!Files.exists(ledger(store))) return false;
            Set<String> now = new HashSet<>(p.via());
            for (String line : Files.readAllLines(ledger(store), StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                JsonNode j = M.readTree(line);
                if (!"proposed".equals(j.path("event").asText())) continue;
                String a = j.path("a").asText(), c = j.path("c").asText();
                if (!((a.equals(p.a().slug()) && c.equals(p.c().slug())) || (a.equals(p.c().slug()) && c.equals(p.a().slug())))) continue;
                Set<String> then = new HashSet<>(); for (JsonNode v : j.path("via")) then.add(v.asText());
                if (then.isEmpty() || !Collections.disjoint(then, now)) return true;
            }
        } catch (IOException ignored) { }
        return false;
    }

    /** How long a searched middle stays searched. The web changes and a search does not return the same page twice, so a middle that found nothing is asked again after this. */
    static final int TRIED_DAYS = 7;

    /** Middles searched for a pair within {@link #TRIED_DAYS}, backed or not: a night does not pay for the same search twice. */
    static Set<String> triedBefore(LibraryStore store, Area a, Area c) {
        Set<String> out = new HashSet<>();
        try {
            if (!Files.exists(ledger(store))) return out;
            Instant since = Instant.now().minus(java.time.Duration.ofDays(TRIED_DAYS));
            for (String line : Files.readAllLines(ledger(store), StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                JsonNode j = M.readTree(line);
                if (!"tried".equals(j.path("event").asText())) continue;
                try { if (Instant.parse(j.path("at").asText()).isBefore(since)) continue; } catch (Exception e) { continue; }
                String x = j.path("a").asText(), y = j.path("c").asText();
                if ((x.equals(a.slug()) && y.equals(c.slug())) || (x.equals(c.slug()) && y.equals(a.slug()))) for (JsonNode m : j.path("middles")) out.add(m.asText());
            }
        } catch (IOException ignored) { }
        return out;
    }

    static synchronized void recordTried(LibraryStore store, Area a, Area c, List<String> middles) {
        if (middles.isEmpty()) return;
        try {
            ObjectNode o = M.createObjectNode();
            o.put("at", Instant.now().toString()); o.put("event", "tried"); o.put("a", a.slug()); o.put("c", c.slug());
            ArrayNode ms = o.putArray("middles"); middles.forEach(ms::add);
            Files.createDirectories(ledger(store).getParent());
            Files.writeString(ledger(store), o + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
    }

    static synchronized void record(LibraryStore store, Proposal p, String event, String detail) {
        try {
            ObjectNode o = M.createObjectNode();
            o.put("at", Instant.now().toString()); o.put("event", event); o.put("question", p.question());
            o.put("a", p.pair().a().slug()); o.put("c", p.pair().c().slug()); ArrayNode v = o.putArray("via"); p.pair().via().subList(0, Math.min(6, p.pair().via().size())).forEach(v::add);
            o.put("score", p.pair().score()); o.put("hops", p.pair().hops()); o.put("random", p.pair().random()); o.put("sensor", p.pair().sensor());
            ArrayNode ps = o.putArray("paths"); p.pair().paths().forEach(ps::add);
            o.put("settings", p.settings().line()); o.put("by", p.by()); if (detail != null) o.put("detail", detail);
            o.put("evidence", evidence(p.pair()));   // the paths and the outside sources: what the person judges by, and what the run starts from
            Files.createDirectories(ledger(store).getParent());
            Files.writeString(ledger(store), o + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
    }

    /** A fate for a proposal already on the ledger, by its question: kept (with the run's job id) or dismissed. */
    static synchronized void fate(LibraryStore store, String question, String event, String detail) {
        try {
            ObjectNode o = M.createObjectNode();
            o.put("at", Instant.now().toString()); o.put("event", event); o.put("question", question); if (detail != null) o.put("detail", detail);
            Files.createDirectories(ledger(store).getParent());
            Files.writeString(ledger(store), o + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
    }

    /** The open proposals. */
    public static List<Frontier.Line> open(LibraryStore store) throws IOException {
        List<Frontier.Line> out = new ArrayList<>();
        for (Frontier.Line l : Frontier.read(store)) if (l.open() && "bridge".equals(l.type())) out.add(l);
        return out;
    }

    /** The measurement, from the ledger: proposed, kept, dismissed, and of the kept ones how many runs ended with claims. */
    public static ObjectNode measure(LibraryStore store) throws IOException {
        int proposed = 0, kept = 0, dismissed = 0, corroborated = 0;
        Map<String, int[]> bySettings = new LinkedHashMap<>();
        Map<String, String> settingOf = new HashMap<>();
        if (Files.exists(ledger(store))) for (String line : Files.readAllLines(ledger(store), StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode j = M.readTree(line);
            String ev = j.path("event").asText(), q = j.path("question").asText();
            switch (ev) {
                case "proposed" -> { proposed++; settingOf.put(q, j.path("settings").asText()); bySettings.computeIfAbsent(j.path("settings").asText(), k -> new int[3])[0]++; }
                case "kept" -> { kept++; String st = settingOf.get(q); if (st != null) bySettings.get(st)[1]++; }
                case "dismissed" -> { dismissed++; String st = settingOf.get(q); if (st != null) bySettings.get(st)[2]++; }
                case "corroborated" -> corroborated++;
                default -> { }
            }
        }
        ObjectNode r = M.createObjectNode();
        r.put("proposed", proposed); r.put("kept", kept); r.put("dismissed", dismissed); r.put("corroborated", corroborated);
        ArrayNode bs = r.putArray("by_settings");
        for (Map.Entry<String, int[]> e : bySettings.entrySet()) bs.addObject().put("settings", e.getKey()).put("proposed", e.getValue()[0]).put("kept", e.getValue()[1]).put("dismissed", e.getValue()[2]);
        return r;
    }

    /** A concept of one area and a concept of the other, and how alike they are by the embedder. */
    record Near(String a, String c, double cosine) { }

    /**
     * The {@code n} nearest concept pairs across two areas by embedding cosine, closest first; empty when there is
     * nothing to compare; null when no embedder answers. This is the library's own evidence of where two areas come
     * closest, computed before any model is asked.
     */
    static List<Near> nearestConcepts(Area a, Area c, Embeddings.Embedder embedder, int n) {
        List<String> ca = restsOn(a), cc = restsOn(c);
        if (embedder == null || "none".equals(embedder.modelId())) return null;
        if (ca.isEmpty() || cc.isEmpty()) return List.of();
        try {
            List<String> all = new ArrayList<>(ca); all.addAll(cc);
            List<float[]> v = embedder.embedAll(all);
            if (v == null || v.size() != all.size()) return null;
            List<double[]> scored = new ArrayList<>();
            for (int i = 0; i < ca.size(); i++) for (int j = 0; j < cc.size(); j++) if (v.get(i) != null && v.get(ca.size() + j) != null) scored.add(new double[]{Walk.cosine(v.get(i), v.get(ca.size() + j)), i, j});
            scored.sort((x, y) -> Double.compare(y[0], x[0]));
            List<Near> out = new ArrayList<>();
            for (double[] sc : scored.subList(0, Math.min(n, scored.size()))) out.add(new Near(ca.get((int) sc[1]), cc.get((int) sc[2]), sc[0]));
            return out;
        } catch (Exception e) { return null; }
    }

    /** How many of the nearest concept pairs the open mode takes as middles and shows the model. */
    static final int NEAR_CONCEPTS = org.researchzosho.Config.getInt("RESEARCHZOSHO_BRIDGES_NEAR_CONCEPTS", 3);

    /**
     * The middles the map itself points at: each side of the nearest concept pairs, closest first, without repeats.
     * A concept that belongs to one area alone costs one search and finds nothing on the other side; that is the
     * price of not choosing by hand.
     */
    static List<String> mapMiddles(List<Near> near) {
        List<String> out = new ArrayList<>();
        if (near == null) return out;
        for (Near x : near.subList(0, Math.min(NEAR_CONCEPTS, near.size()))) for (String m : List.of(x.a(), x.c())) {
            String w = m.toLowerCase(Locale.ROOT).strip();
            if (w.isEmpty() || w.length() > 60 || w.split("\\s+").length > 4 || STOP.contains(w) || out.contains(w)) continue;
            out.add(w);
        }
        return out;
    }

    // ---- distance between two areas: how far apart, and how close the nearest concepts are ----

    /** What separates two areas: hops on the map, whether they are siblings, shared terms, paths, sources naming both, and the nearest concept pairs by embedding. */
    public static ObjectNode distance(LibraryStore store, String areaA, String areaC) throws IOException {
        List<Area> areas = areas(store);
        Area a = null, c = null;
        for (Area x : areas) { if (x.slug().equals(areaA) || x.label().equalsIgnoreCase(areaA) || x.slug().endsWith("--" + areaA)) a = x; if (x.slug().equals(areaC) || x.label().equalsIgnoreCase(areaC) || x.slug().endsWith("--" + areaC)) c = x; }
        if (a == null || c == null) throw new IOException("no area named " + (a == null ? areaA : areaC) + "; the areas are: " + String.join(", ", areas.stream().map(Area::slug).toList()));
        Graph g = Graph.build(store);
        Embeddings.Embedder embedder = Embeddings.configured();
        Walk walk = new Walk(g, embedder);
        ObjectNode r = M.createObjectNode();
        r.put("a", a.slug()); r.put("c", c.slug()); r.put("a_label", a.label()); r.put("c_label", c.label());
        int hops = hops(g, "subject:" + a.slug(), "subject:" + c.slug());
        r.put("hops_on_map", hops); r.put("siblings", facet(a.slug()).equals(facet(c.slug())));
        Set<String> shared = new java.util.TreeSet<>(); for (String t : a.headTerms()) if (c.headTerms().contains(t) && !a.labelWords().contains(t) && !c.labelWords().contains(t)) shared.add(t);
        ArrayNode st = r.putArray("shared_terms"); shared.forEach(st::add);
        ArrayNode ps = r.putArray("paths"); walk.paths(a, c, 3).forEach(ps::add);
        Set<String> sharedSources = new HashSet<>(a.sources()); sharedSources.retainAll(c.sources());
        r.put("shared_sources", sharedSources.size());
        r.put("sources_naming_both", coMentions(a, c, store.scanFindings().findings(), rawTexts(store)));
        // the nearest concepts across the two areas: what the fold would join, and how close it came
        r.put("concepts_a", restsOn(a).size()); r.put("concepts_c", restsOn(c).size());
        ArrayNode nearest = r.putArray("nearest_concepts");
        List<Near> near = nearestConcepts(a, c, embedder, 8);
        if (near != null) for (Near n : near) nearest.addObject().put("a", n.a()).put("c", n.c()).put("cosine", Math.round(n.cosine() * 1000) / 1000.0).put("folded", n.cosine() >= Walk.FOLD);
        boolean embedded = near != null;
        r.put("embedder", embedded ? embedder.modelId() : "none answering: concept names must match exactly");
        r.put("fold_at", Walk.FOLD);
        String verdict = !ps.isEmpty() ? "joined on the map: " + ps.get(0).asText()
                : !shared.isEmpty() ? "share the term(s) " + String.join(", ", shared) + " and no path yet"
                : nearest.size() > 0 ? "no shared term and no path; the nearest concepts are \"" + nearest.get(0).path("a").asText() + "\" and \"" + nearest.get(0).path("c").asText() + "\" at cosine " + nearest.get(0).path("cosine").asText() + (nearest.get(0).path("folded").asBoolean() ? " (folded)" : " (below the fold at " + Walk.FOLD + ")")
                : "no shared term, no path, and no embedder to say how near the concepts come";
        r.put("summary", a.label() + " ↔ " + c.label() + ": " + (hops < 0 ? "not connected on the map" : hops + " hop(s) on the map") + (r.path("siblings").asBoolean() ? ", siblings" : "") + "; " + verdict + (r.path("sources_naming_both").asInt() > 0 ? "; " + r.path("sources_naming_both").asInt() + " source(s) name both" : "; no source names both"));
        return r;
    }

    // ---- settings per area, in catalog/bridges.md ----

    static Path settingsFile(LibraryStore store) { return store.root().resolve("catalog").resolve("bridges.md"); }

    /** The settings for an area: the file's defaults, then the area's own line. */
    public static Settings settings(LibraryStore store, String area) throws IOException {
        Settings s = Settings.defaults();
        if (!Files.exists(settingsFile(store))) return s;
        Settings forArea = null;
        for (String line : Files.readAllLines(settingsFile(store), StandardCharsets.UTF_8)) {
            if (!line.startsWith("- ")) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String name = line.substring(2, colon).strip();
            Settings t = name.equals("default") ? s : (area != null && name.equals(area)) ? (forArea == null ? s : forArea) : null;
            if (t == null) continue;
            for (String kv : line.substring(colon + 1).strip().split("\\s+")) {
                int eq = kv.indexOf('=');
                if (eq < 0) { if (kv.equals("strict") || kv.equals("loose")) t = t.with(kv, "true"); continue; }
                t = t.with(kv.substring(0, eq), kv.substring(eq + 1));
            }
            if (name.equals("default")) s = t; else forArea = t;
        }
        return forArea == null ? s : forArea;
    }

    /** Write one area's (or the default) settings line. */
    public static synchronized void setSettings(LibraryStore store, String area, Settings s) throws IOException {
        String name = area == null || area.isBlank() ? "default" : area;
        List<String> lines = Files.exists(settingsFile(store)) ? new ArrayList<>(Files.readAllLines(settingsFile(store), StandardCharsets.UTF_8))
                : new ArrayList<>(List.of("# Bridges — the dials, per area (docs/DESIGN_BRIDGES.md)", "", "One line each: `- <area or default>: sources=library,peers,web reach=low|medium|high strict|loose toward=<area> away=<words> since=<date> per_night=<n>`.", ""));
        String row = "- " + name + ": " + s.line() + " per_night=" + s.perNight();
        boolean done = false;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).startsWith("- " + name + ":")) { lines.set(i, row); done = true; }
        if (!done) lines.add(row);
        Files.createDirectories(settingsFile(store).getParent());
        Files.write(settingsFile(store), lines, StandardCharsets.UTF_8);
    }

    /** The nightly step: the hottest areas, each area's own settings for the count, {@code perNight} proposals in all. */
    public static String nightly(LibraryStore store, Researcher.Drive drive, Researcher.Tools web) throws IOException {
        Settings s = settings(store, null);
        if (s.perNight() <= 0) return "off (per_night=0)";
        ObjectNode r = propose(store, drive, web, s, null, false, "crew:bridges", new Random());
        return r.path("summary").asText();
    }
}
