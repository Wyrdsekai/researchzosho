package org.researchzosho.librarian;

import org.researchzosho.records.RecordSource;
import org.researchzosho.records.RecordSources;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.time.Instant;
import java.time.LocalDate;
import org.researchzosho.tools.Fetch;

/**
 * What a claim rests on, the way a genealogist asks it: a RECORD (a register, a newspaper, a patent, a scan of a directory: a
 * thing made at the time, by somebody in a position to know), something PUBLISHED about the person later (an encyclopedia, a
 * scholarly paper, a book), or a CLUE (a family's own account, somebody else's tree, a hint, a web page). A clue is where the
 * search starts and never where it ends: "just because it's there forty times doesn't mean it's true". Computed from the
 * sources' locators, by host, never stored and never a model's opinion, like {@link SourceTier}.
 */
public enum Evidence {
    record, published, clue;

    /** Sites that hold trees people made, and hints: a place to start, whatever the site's size. */
    static final Set<String> TREE_SITES = Set.of("geni.com", "wikitree.com", "ancestry.com", "ancestry.co.uk", "ancestry.jp", "myheritage.com", "familysearch.org",
            "findagrave.com", "keibatsugaku.com", "reichsarchiv.jp", "werelate.org", "gw.geneanet.org", "geneanet.org", "rootsweb.com", "familytreenow.com");

    public static Evidence of(String locator) {
        String l = locator == null ? "" : locator.strip();
        if (l.startsWith("told://") || l.startsWith("file://") || l.isEmpty()) return clue;
        if (l.startsWith("cite:")) return record;
        String host = host(l);
        if (host != null) {
            if (TREE_SITES.stream().anyMatch(t -> host.equals(t) || host.endsWith("." + t))) return clue;
            if (host.endsWith("wikipedia.org")) return published;   // an encyclopedia article is published about the person, not somebody's tree
            for (RecordSource s : RecordSources.all()) {
                if (s.hosts().stream().noneMatch(h -> host.equals(h) || host.endsWith("." + h))) continue;
                return switch (s.kind()) { case "index", "code", "model", "package" -> clue; default -> record; };
            }
        }
        return switch (SourceTier.of(l)) { case reference, scholarly -> published; case primary -> published; default -> clue; };
    }

    /** The best a claim's sources give it. */
    public static Evidence of(Finding f) {
        Evidence best = clue;
        for (Finding.Source s : f.sources()) { Evidence e = of(s.locator()); if (e.ordinal() < best.ordinal()) best = e; }
        return best;
    }

    public static Evidence of(List<String> locators) {
        Evidence best = clue;
        for (String l : locators) { Evidence e = of(l); if (e.ordinal() < best.ordinal()) best = e; }
        return best;
    }

    /**
     * One fact whichever way round a claim wrote it: a field whose relations have two ways round says which key both give ("A parent-of
     * B" and "B child-of A" are one fact, {@link Profile#factKey}); any other relation is the one way it was written. A field is asked only
     * about its own work: a claim that is the field's ({@code fields}, from {@link Fields#ofClaim}), or any claim when the graph is read
     * the field's own way ({@code lens}: its own commands and pages). A null lens is the core's reading.
     */
    public static String factKey(Profile lens, Set<String> fields, String from, String predicate, String to) {
        for (Profile p : Profiles.known()) {
            if (!(lens != null && lens.name().equals(p.name())) && !fields.contains(p.name())) continue;
            String k = p.factKey(from, predicate, to);
            if (k != null) return k;
        }
        return from + "\t" + predicate + "\t" + to;
    }

    /**
     * The best that any claim gives each fact, keyed by {@link #factKey} over node ids, as {@code lens} reads the graph; {@code runs} is the
     * fields ledger ({@link Fields#runs}). A family account and a record that say the same thing are two claims, and the fact rests on the
     * record. A disputed claim adds nothing.
     */
    public static Map<String, Evidence> byFact(Graph g, Map<String, Finding> findings, Profile lens, Map<String, Set<String>> runs) {
        Map<String, Evidence> out = new LinkedHashMap<>();
        for (Graph.Edge e : g.edges()) {
            Finding f = findings.get(e.findingId());
            if (f == null || e.disputed()) continue;
            out.merge(factKey(lens, Fields.ofClaim(f, runs), e.from(), e.predicate(), e.to()), of(f), (a, b) -> a.ordinal() <= b.ordinal() ? a : b);
        }
        return out;
    }

    /** What one claim's fact rests on: the best of its own sources and of every other claim that says the same. */
    public static Evidence ofFact(Finding f, Map<String, Evidence> byFact, String key) {
        Evidence own = of(f);
        if (f.state() == Finding.State.disputed) return own;
        Evidence best = byFact.getOrDefault(key, own);
        return best.ordinal() < own.ordinal() ? best : own;
    }

    /**
     * The claims that cite one source, the way a person sees what a doubtful book or page holds up: {@code alone} are the claims whose
     * fact has no other source (the same fact from another claim counts), {@code backed} those another source also gives.
     */
    public record Resting(List<Finding> alone, List<Finding> backed) {
        public boolean isEmpty() { return alone.isEmpty() && backed.isEmpty(); }
    }

    /**
     * Every claim that cites {@code source}: a locator, a web address in any spelling, or a file's name ("community.pdf"). Retired and
     * replaced claims are left out, and so is {@code except} (the claim the person is disputing now).
     */
    public static Resting restingOn(LibraryStore store, String source, String except) throws IOException { return restingOn(store, source, except, null); }

    /**
     * The same, with the graph read as {@code lens} reads it ({@link Graph#build(LibraryStore, Profile)}): a field's own command counts
     * every claim that says the same fact in its relations, as the field's other commands do. Null is the core graph.
     */
    public static Resting restingOn(LibraryStore store, String source, String except, Profile lens) throws IOException {
        Graph g = Graph.build(store, lens);
        Map<String, Set<String>> runs = Fields.runs(store);
        Map<String, Finding> byId = new LinkedHashMap<>();
        for (Finding f : store.scanFindings().findings()) byId.put(f.id(), f);
        // every source of every live claim of a fact, so that a fact another claim also backs is not called alone
        Map<String, Set<String>> factSources = new LinkedHashMap<>();
        Map<String, String> keyOf = new LinkedHashMap<>();
        for (Graph.Edge e : g.edges()) {
            Finding f = byId.get(e.findingId());
            if (f == null || e.disputed() || e.predicate().equals("is filed under") || e.predicate().equals("mentions")) continue;
            String key = factKey(lens, Fields.ofClaim(f, runs), e.from(), e.predicate(), e.to());
            keyOf.put(f.id(), key);
            for (Finding.Source s : f.sources()) factSources.computeIfAbsent(key, k -> new HashSet<>()).add(s.locator());
        }
        List<Finding> alone = new ArrayList<>(), backed = new ArrayList<>();
        for (Finding f : byId.values()) {
            if (f.state() == Finding.State.retired || f.state() == Finding.State.superseded || f.id().equals(except)) continue;
            if (f.sources().stream().noneMatch(s -> cites(store, s.locator(), source))) continue;
            Set<String> others = new HashSet<>();
            for (Finding.Source s : f.sources()) if (!cites(store, s.locator(), source)) others.add(s.locator());
            String key = keyOf.get(f.id());
            if (key != null) for (String l : factSources.getOrDefault(key, Set.of())) if (!cites(store, l, source)) others.add(l);
            (others.isEmpty() ? alone : backed).add(f);
        }
        return new Resting(alone, backed);
    }

    /**
     * Whether a locator is the source a person named: the same locator, the same address, or a file of that name. A file of a cloned
     * repository is one source whichever of its lines a claim cites, and so is its page on a forge ({@link CodeTool#clonedFileOf}), as the
     * independence count takes it.
     */
    static boolean cites(LibraryStore store, String locator, String source) {
        if (locator == null || source == null || source.isBlank()) return false;
        String s = source.strip();
        if (sameLocator(locator, s)) return true;
        String file = codeFile(store, s);
        if (file != null && file.equals(codeFile(store, locator))) return true;
        if (!s.contains("/") && locator.startsWith("file:")) return locator.substring(locator.lastIndexOf('/') + 1).equalsIgnoreCase(s);
        return false;
    }

    /** The cloned repository's file a locator names, by its lines or by a forge's page of it; null for any other locator. */
    private static String codeFile(LibraryStore store, String locator) {
        String file = CodeTool.fileOf(locator);
        return file != null || locator == null || !locator.startsWith("http") ? file : CodeTool.clonedFileOf(store, locator);
    }

    /** What rests on a source, said to a person: the claims that would have nothing behind them if it is wrong, and the others. Empty when nothing does. */
    public static String restingForPerson(String source, Resting r, boolean afterDispute) {
        if (r.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        int n = r.alone().size() + r.backed().size();
        b.append(afterDispute ? "The same source, " + source + ", is behind " + n + (n == 1 ? " other fact" : " other facts") + " in your library."
                : n + (n == 1 ? " fact" : " facts") + " in your library rest on " + source + ".").append('\n');
        if (!r.alone().isEmpty()) {
            b.append("\n  Facts that have no other source (").append(r.alone().size()).append("). If this source is wrong, nothing else holds them up:\n");
            for (Finding f : r.alone()) b.append("    ").append(f.id().replaceFirst("^(F-\\d+).*", "$1")).append("  ").append(f.title()).append("  [").append(f.state()).append("]\n");
        }
        if (!r.backed().isEmpty()) {
            b.append("\n  Facts that another source also gives (").append(r.backed().size()).append("). They stand if this source is wrong:\n");
            for (Finding f : r.backed()) b.append("    ").append(f.id().replaceFirst("^(F-\\d+).*", "$1")).append("  ").append(f.title()).append("  [").append(f.state()).append("]\n");
        }
        String first = (!r.alone().isEmpty() ? r.alone() : r.backed()).get(0).id().replaceFirst("^(F-\\d+).*", "$1");
        b.append("\nTo say that one of these facts is wrong, and why, give the command researchzosho dispute with the fact's code from the list, for example:\n    researchzosho dispute ").append(first).append(" \"the reason, in your own words\"\n");
        return b.toString();
    }

    /**
     * How many years after the event the sources wrote it down, when a field can tell from what its sources say of themselves ({@link
     * Profile#writtenLater}); 0 otherwise.
     */
    public static int writtenLater(Finding f) {
        int most = 0;
        for (Profile p : Profiles.known()) most = Math.max(most, p.writtenLater(f));
        return most;
    }

    /**
     * A fact the library holds, said again by another source. A draft or an accepted claim gains the source, with the words that say it, and
     * keeps its state. A claim the person disputed or retired stays as they left it and gets a note saying where the fact came back: their
     * decision holds against later sources. A claim the library disputed or retired by itself ({@link Finding#setAsideBy}) gains the source
     * and keeps its state, so later sources can settle it. Returns the claim as written, or null when it already had these sources and
     * nothing changed. A review the person signed is left as it was, so the claim comes back to them with its new source; a review the
     * library signed follows the new sources.
     */
    public static Finding toldAgain(LibraryStore store, Finding held, List<Finding.Source> sources, String by, String words) throws IOException {
        return toldAgain(store, held, sources, by, words, List.of());
    }

    /**
     * {@code extra}: what the new source's claim would have carried, such as what its words were checked against when it was read from
     * a picture. A draft or an accepted claim that gains the source gains these notes too, each once.
     */
    public static Finding toldAgain(LibraryStore store, Finding held, List<Finding.Source> sources, String by, String words, List<Finding.Note> extra) throws IOException {
        List<Finding.Source> fresh = new ArrayList<>();
        for (Finding.Source s : sources) if (held.sources().stream().noneMatch(h -> sameLocator(h.locator(), s.locator())) && fresh.stream().noneMatch(h -> sameLocator(h.locator(), s.locator()))) fresh.add(s);
        if (fresh.isEmpty()) return null;
        String where = String.join(", ", fresh.stream().map(Finding.Source::locator).distinct().toList());
        String said = words == null || words.isBlank() ? "" : ": \"" + Acquisitions.compress(words, 160) + "\"";
        Finding out;
        if (held.setAsideBy() == Finding.SetAside.person) {
            String text = "said again by " + where + said;
            if (held.notes().stream().anyMatch(n -> n.kind().equals("met-again") && n.text().startsWith("said again by " + where))) return null;
            out = held.withNote(new Finding.Note("met-again", by, LocalDate.now().toString(), text));
        } else {
            List<Finding.Source> all = new ArrayList<>(held.sources());
            all.addAll(fresh);
            List<Finding.Note> notes = new ArrayList<>(held.notes());
            notes.add(new Finding.Note("source-added", by, LocalDate.now().toString(), "also said by " + where + said
                    + (held.setAsideBy() == Finding.SetAside.library ? "; the claim stays " + held.state() + ", as the library left it, until the sources settle it" : "")));
            for (Finding.Note n : extra == null ? List.<Finding.Note>of() : extra) if (notes.stream().noneMatch(x -> x.kind().equals(n.kind()) && x.text().equals(n.text()))) notes.add(n);
            Finding grown = new Finding(held.id(), held.title(), held.subjects(), held.state(), held.claimType(), held.confidence(), held.writer(), held.recordedAt(), held.validAsOf(),
                    held.volatility(), held.reviewBy(), all, held.supersedes(), held.review(), held.body(), held.triple(), notes);
            Finding.Review r = held.review();
            boolean library = r != null && !r.reviewer().equals("person") && !held.reviewStale();
            out = library ? new Finding(grown.id(), grown.title(), grown.subjects(), grown.state(), grown.claimType(), grown.confidence(), grown.writer(), grown.recordedAt(), grown.validAsOf(),
                    grown.volatility(), grown.reviewBy(), grown.sources(), grown.supersedes(), new Finding.Review(r.round() + 1, by, r.decision(), grown.contentHash(), Instant.now().toString()),
                    grown.body(), grown.triple(), grown.notes()) : grown;
            Changes.append(store, "finding", held.id(), "edited", "a further source: " + where);
        }
        store.write(out);
        return out;
    }

    /** One address in two spellings (http and https, a trailing slash, tracking words) is one source. */
    public static boolean sameLocator(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        if (a.startsWith("http") && b.startsWith("http")) { String ca = address(a), cb = address(b); return ca != null && ca.equals(cb); }
        return false;
    }

    private static String address(String url) {
        String c = Fetch.canonical(url);
        return c == null ? null : c.replaceFirst("^https?://(www\\.)?", "").replaceAll("/+$", "");
    }

    /** The word a person reads. */
    public String word() { return switch (this) { case record -> "a record"; case published -> "published"; case clue -> "a clue only"; }; }

    private static String host(String l) {
        try { String h = URI.create(l).getHost(); return h == null ? null : h.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", ""); } catch (RuntimeException e) { return null; }
    }
}
