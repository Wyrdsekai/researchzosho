package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A dossier (0.6.6): everything the library holds on one subject, a person or an organisation, as one document: the relations the graph
 * has, the claims by the class of their source, a timeline, what the subject or a person told the library, the reports to dive into, the open
 * questions, what was looked for and not found, and the subjects it links to. It is a view over the shelves, written fresh each time it is
 * asked for; a research run adds claims, it does not own the page (the first dossier run was read as one report and missed what the shelves
 * already held about the subject, 2026-10-24). The story, chapter by chapter, is the reports: each is a page to dive into.
 */
public final class Dossier {
    private static final ObjectMapper M = new ObjectMapper();
    static final Pattern YEAR = Pattern.compile("\\b(1[5-9]\\d\\d|20\\d\\d)\\b");

    private Dossier() { }

    /** What the shelves hold on a subject, gathered once: the view and the story are both written from it. */
    public record Gathered(String name, Graph.Node node, List<Graph.Edge> relations, Graph graph, List<Finding> claims, Map<SourceClass, List<Finding>> byClass, List<Investigation> reports, List<Frontier.Line> questions, String kind) {
        public String label() { return node == null ? name : node.label(); }
    }

    /** The dossier as JSON: {subject, found, kind, aliases, claims, reports, questions, by_class, markdown}. */
    public static ObjectNode of(LibraryStore store, String subject) throws IOException { return of(store, subject, Translate.AS_IS); }

    /** The dossier as JSON, its markdown built with {@code w}. */
    public static ObjectNode of(LibraryStore store, String subject, Translate.Words w) throws IOException {
        Gathered d = gather(store, subject);
        ObjectNode r = M.createObjectNode();
        r.put("subject", d.label()); r.put("found", d.node() != null || !d.claims().isEmpty() || !d.reports().isEmpty());
        r.put("kind", d.kind());
        ArrayNode al = r.putArray("aliases"); if (d.node() != null) for (String a : d.node().aliases()) al.add(a);
        r.put("claims", d.claims().size()); r.put("reports", d.reports().size()); r.put("questions", d.questions().size());
        ObjectNode bc = r.putObject("by_class"); for (var e : d.byClass().entrySet()) bc.put(e.getKey().label(), e.getValue().size());
        List<CrossLinks.Link> links = CrossLinks.of(store, d.label());
        r.put("links", links.size());
        r.put("markdown", markdown(d.name(), d.node(), d.relations(), d.graph(), d.claims(), d.byClass(), d.reports(), d.questions(), d.kind(), w) + linksMarkdown(links) + analysisMarkdown(store, d, w));
        return r;
    }

    /** "## Shared with other dossiers": the names this subject's claims share with another subject's, each side cited; "" when none. */
    static String linksMarkdown(List<CrossLinks.Link> links) {
        if (links.isEmpty()) return "";
        StringBuilder b = new StringBuilder("\n## Shared with other dossiers\n\nA name both dossiers' claims carry; what connects them is a question, not a claim, until a run or a person answers it.\n\n");
        for (CrossLinks.Link l : links) b.append("- ").append(l.name()).append(" — also in the dossier of ").append(l.other()).append(" [").append(String.join(", ", l.ownClaims())).append(" | ").append(String.join(", ", l.otherClaims())).append("]\n");
        return b.toString();
    }

    public static Gathered gather(LibraryStore store, String subject) throws IOException {
        String name = subject == null ? "" : subject.strip();
        if (name.isEmpty()) throw ProtocolError.invalidArgs("A dossier is on somebody or something: give the name.");
        String low = name.toLowerCase(Locale.ROOT);
        Map<String, Finding> byId = new LinkedHashMap<>();
        for (Finding f : store.scanFindings().findings()) byId.put(f.id(), f);
        // the graph's node, when the subject is one: its names and its relations
        Graph g = Graph.build(store);
        String nodeId = g.nodeIdOf(name);
        Graph.Node node = nodeId == null ? null : g.node(nodeId);
        List<String> names = new ArrayList<>(); names.add(low);
        if (node != null) { names.add(node.label().toLowerCase(Locale.ROOT)); for (String a : node.aliases()) names.add(a.toLowerCase(Locale.ROOT)); }
        List<Graph.Edge> relations = new ArrayList<>();
        if (node != null) for (Graph.Edge e : g.edges()) if (nodeId.equals(e.from()) || nodeId.equals(e.to())) relations.add(e);
        // the claims that name the subject, in their title, their body or their triple
        List<Finding> claims = new ArrayList<>();
        for (Finding f : byId.values()) {
            if (f.state() == Finding.State.retired || f.state() == Finding.State.superseded) continue;
            boolean mine = false;
            if (f.triple() != null) for (String n : names) if (f.triple().subject().toLowerCase(Locale.ROOT).equals(n) || f.triple().object().toLowerCase(Locale.ROOT).equals(n)) mine = true;
            if (!mine) { String t = (f.title() + "\n" + f.body()).toLowerCase(Locale.ROOT); for (String n : names) if (t.contains(n)) mine = true; }
            if (mine) claims.add(f);
        }
        for (Graph.Edge e : relations) { Finding f = byId.get(e.findingId()); if (f != null && !claims.contains(f) && f.state() != Finding.State.retired) claims.add(f); }
        Map<SourceClass, List<Finding>> byClass = new EnumMap<>(SourceClass.class);
        for (Finding f : claims) byClass.computeIfAbsent(SourceClass.of(f.sources()), k -> new ArrayList<>()).add(f);
        // the reports about the subject: the question names it
        List<Investigation> reports = new ArrayList<>();
        if (Files.isDirectory(store.investigationsDir())) {
            List<Path> files; try (var s = Files.list(store.investigationsDir())) { files = s.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().toList(); }
            for (Path p : files) {
                String id = p.getFileName().toString().replaceFirst("\\.md$", "");
                Investigation inv; try { inv = store.investigation(id); } catch (RuntimeException | IOException e) { continue; }
                if (inv == null) continue;
                String t = inv.title().toLowerCase(Locale.ROOT); boolean about = false; for (String n : names) if (t.contains(n)) about = true;
                if (!about) { String head = inv.body().length() > 1500 ? inv.body().substring(0, 1500).toLowerCase(Locale.ROOT) : inv.body().toLowerCase(Locale.ROOT); for (String n : names) if (head.contains(n)) about = true; }
                if (about) reports.add(inv);
            }
        }
        List<Frontier.Line> questions = new ArrayList<>();
        for (Frontier.Line l : Frontier.read(store)) {   // a question that names the subject, or one filed for its dossier ("asked analysis <subject>", "asked crosslinks <subject>") whoever it names
            if (!l.open()) continue;
            String t = l.text().toLowerCase(Locale.ROOT), k = l.kind().toLowerCase(Locale.ROOT);
            for (String n : names) if (t.contains(n) || k.contains(n)) { questions.add(l); break; }
        }
        return new Gathered(name, node, relations, g, claims, byClass, reports, questions, kindOf(reports, Fields.runs(store), node));
    }

    /** A number, a span of years or an amount of money the writer put where a subject goes: "34", "1999 to 2006", "$68 billion". */
    static boolean isASpan(String label) {
        return label != null && label.matches("(?i)^[$€£¥]?\\s?\\d[\\d\\s.,–-]*(?:\\s*to\\s*\\d[\\d\\s.,–-]*)?(?:\\s*(?:trillion|billion|million|thousand|bn|m|k|%|percent))?$");
    }

    /** The dossier's kind: person or organization as a report's run was asked for, else what the graph's node says of it. */
    static String kindOf(List<Investigation> reports, Map<String, Set<String>> runs, Graph.Node node) {
        for (Investigation inv : reports) {
            Set<String> ran = runs.getOrDefault(inv.id(), Set.of());
            if (ran.contains("person")) return "person";
            if (ran.contains("organization")) return "organization";
        }
        return node == null ? "" : node.kind();
    }

    static String markdown(String name, Graph.Node node, List<Graph.Edge> relations, Graph g, List<Finding> claims, Map<SourceClass, List<Finding>> byClass, List<Investigation> reports, List<Frontier.Line> questions) {
        return markdown(name, node, relations, g, claims, byClass, reports, questions, node == null ? "" : node.kind());
    }

    static String markdown(String name, Graph.Node node, List<Graph.Edge> relations, Graph g, List<Finding> claims, Map<SourceClass, List<Finding>> byClass, List<Investigation> reports, List<Frontier.Line> questions, String kind) {
        return markdown(name, node, relations, g, claims, byClass, reports, questions, kind, Translate.AS_IS);
    }

    /** The printout, built with {@code w}: every piece of prose the reports and the claims give passes through it; the labels, names, ids and dates do not. */
    static String markdown(String name, Graph.Node node, List<Graph.Edge> relations, Graph g, List<Finding> claims, Map<SourceClass, List<Finding>> byClass, List<Investigation> reports, List<Frontier.Line> questions, String kind, Translate.Words w) {
        StringBuilder b = new StringBuilder();
        String label = node == null ? name : node.label();
        b.append("# Dossier: ").append(label).append("\n\n");
        StringBuilder head = new StringBuilder();
        if (node != null) { head.append(kind); if (!node.aliases().isEmpty()) head.append(" · also ").append(String.join(", ", node.aliases())); if (node.wikidata() != null && !node.wikidata().isBlank()) head.append(" · Wikidata ").append(node.wikidata()); head.append(" · "); }
        head.append(claims.size()).append(claims.size() == 1 ? " claim" : " claims");
        if (!byClass.isEmpty()) { List<String> parts = new ArrayList<>(); for (var e : byClass.entrySet()) parts.add(e.getValue().size() + " " + e.getKey().label()); head.append(" (").append(String.join(", ", parts)).append(")"); }
        head.append(" · ").append(reports.size()).append(reports.size() == 1 ? " report" : " reports").append(" · ").append(questions.size()).append(" open question").append(questions.size() == 1 ? "" : "s").append(" · as of ").append(LocalDate.now());
        b.append(head).append("\n\n");
        if (claims.isEmpty() && reports.isEmpty() && node == null) { b.append("The library holds nothing on this subject yet. A research run starts it: researchzosho research ask \"Who is ").append(name).append("?\"\n"); return b.toString(); }
        String work = work(reports);
        if (!work.isEmpty()) { b.append("## What they do\n\n").append(w.tr(work)).append("\n\n"); String src = sourcesLine(work, reportOf(reports, "What they do", "The work", "Material")); if (!src.isEmpty()) b.append(src).append("\n\n"); }
        for (String[] f : FACETS) {
            String[] names = f[1].split("\\|"); String s = latestSection(reports, names);
            if (s.isEmpty()) continue;
            b.append("## ").append(f[0]).append("\n\n").append(w.tr(s)).append("\n\n");
            String src = sourcesLine(s, reportOf(reports, names)); if (!src.isEmpty()) b.append(src).append("\n\n");
        }
        b.append(PatternOfLife.markdown(PatternOfLife.of(claims)));
        if (!relations.isEmpty()) {
            b.append("## Relations\n\n");
            Map<String, List<String>> byPred = new TreeMap<>();
            for (Graph.Edge e : relations) {
                boolean out = node != null && node.id().equals(e.from());
                Graph.Node other = g.node(out ? e.to() : e.from());
                String otherLabel = other == null ? (out ? e.to() : e.from()) : other.label() + (other.kind().isEmpty() ? "" : " (" + other.kind() + ")");
                byPred.computeIfAbsent((out ? "" : "← ") + w.tr(e.predicate()), k -> new ArrayList<>()).add(otherLabel + (e.disputed() ? " (disputed)" : "") + " [" + e.findingId() + "]");
            }
            for (var e : byPred.entrySet()) b.append("- ").append(e.getKey()).append(": ").append(String.join("; ", e.getValue())).append('\n');
            b.append('\n');
        }
        // the timeline: the year a claim names, else the date it held
        Map<String, List<Finding>> byYear = new TreeMap<>(); List<Finding> undated = new ArrayList<>();
        for (Finding f : claims) { String y = yearOf(f); if (y == null) undated.add(f); else byYear.computeIfAbsent(y, k -> new ArrayList<>()).add(f); }
        if (!byYear.isEmpty()) {
            b.append("## Timeline\n\n");
            for (var e : byYear.entrySet()) for (Finding f : e.getValue()) b.append("- ").append(e.getKey()).append(": ").append(line(f, w)).append('\n');
            b.append('\n');
        }
        List<Finding> decisions = decisions(claims);
        if (!decisions.isEmpty()) {
            b.append("## Decisions\n\nWhat the people in power decided, dated, with the reason they gave.\n\n");
            for (Finding f : decisions) b.append("- ").append(yearOf(f) == null ? "" : yearOf(f) + ": ").append(line(f, w)).append('\n');
            b.append('\n');
        }
        List<Finding> said = statements(claims);
        if (!said.isEmpty()) {
            b.append("## In their own words\n\nWhat they said, dated and placed, as the record quotes them.\n\n");
            for (Finding f : said) b.append("- ").append(yearOf(f) == null ? "" : yearOf(f) + ": ").append(line(f, w)).append('\n');
            b.append('\n');
        }
        List<Finding> media = media(claims);
        if (!media.isEmpty()) {
            b.append("## Media\n\nPhotographs, video and scans of the subject, each with its provenance: who made it, when, where it is published, the licence.\n\n");
            for (Finding f : media) b.append("- ").append(yearOf(f) == null ? "" : yearOf(f) + ": ").append(line(f, w)).append('\n');
            b.append('\n');
        }
        List<Finding> told = byClass.getOrDefault(SourceClass.told, List.of());
        if (!told.isEmpty()) {
            b.append("## Told\n\nWhat a person told the library in conversation, in their words.\n\n");
            for (Finding f : told) b.append("- ").append(f.validAsOf()).append(", ").append(f.writer()).append(": ").append(w.tr(f.body().strip().replace("\n", " "))).append(" [").append(f.id()).append("]\n");
            b.append('\n');
        }
        for (SourceClass c : new SourceClass[]{SourceClass.publicRecord, SourceClass.ownPublication, SourceClass.supplied, SourceClass.thirdParty}) {
            List<Finding> fs = byClass.get(c); if (fs == null || fs.isEmpty()) continue;
            b.append("## ").append(Character.toUpperCase(c.label().charAt(0))).append(c.label().substring(1)).append("\n\n");
            for (Finding f : fs) if (!byYear.values().stream().anyMatch(l -> l.contains(f))) b.append("- ").append(line(f, w)).append('\n'); else b.append("- ").append(line(f, w)).append('\n');
            b.append('\n');
        }
        String storyTitle = "The story of " + label;
        Investigation story = null;
        for (Investigation inv : reports) if (inv.title().equals(storyTitle)) story = inv;   // the latest, the files being read in order
        if (story != null) {
            int grown = Math.max(0, claims.size() - story.findings().size());
            b.append("## Story\n\n- [").append(storyTitle).append("](/entry/").append(story.id()).append(") — written ").append(story.recordedAt().length() >= 10 ? story.recordedAt().substring(0, 10) : story.recordedAt())
             .append(" from ").append(story.findings().size()).append(" claims, chapter by chapter")
             .append(grown == 0 ? "; current with the shelves" : "; the shelves hold " + grown + " more now — the next run on the subject writes it again, or researchzosho story does").append("\n\n");
        }
        if (!reports.isEmpty()) {
            b.append("## Reports to dive into\n\n");
            for (Investigation inv : reports) if (!inv.title().equals(storyTitle)) b.append("- ").append(inv.id()).append(" — ").append(w.tr(inv.title())).append(" (").append(inv.recordedAt().length() >= 10 ? inv.recordedAt().substring(0, 10) : inv.recordedAt()).append(")\n  ").append(answerOf(inv.body(), 300)).append('\n');
            b.append('\n');
            List<String> notFound = new ArrayList<>();
            for (Investigation inv : reports) notFound.addAll(notFoundLines(inv.body()));
            if (!notFound.isEmpty()) { b.append("## Looked for and not found\n\n"); for (String l : notFound) b.append("- ").append(w.tr(l)).append('\n'); b.append('\n'); }
        }
        if (!questions.isEmpty()) { b.append("## Open questions\n\n"); for (Frontier.Line l : questions) b.append("- ").append(w.tr(l.text())).append('\n'); b.append('\n'); }
        if (!relations.isEmpty()) {
            b.append("## Linked\n\n");
            Map<String, Integer> seen = new LinkedHashMap<>();
            for (Graph.Edge e : relations) { String other = node != null && node.id().equals(e.from()) ? e.to() : e.from(); seen.merge(other, 1, Integer::sum); }
            for (var e : seen.entrySet()) { Graph.Node o = g.node(e.getKey()); if (o == null || isASpan(o.label())) continue;   /* "34", "1999 to 2006": a span the writer put where an organisation belongs, not a subject */ b.append("- ").append(o.label()).append(o.kind().isEmpty() ? "" : " (" + o.kind() + ")").append(": ").append(o.degree()).append(o.degree() == 1 ? " relation" : " relations").append(" of its own\n"); }
            b.append('\n');
        }
        return b.toString().stripTrailing() + "\n";
    }

    static String line(Finding f) { return line(f, Translate.AS_IS); }

    static String line(Finding f, Translate.Words w) {
        String date = f.validAsOf() == null ? "" : f.validAsOf();
        return w.tr(f.title()) + " [" + SourceClass.of(f.sources()).label() + (date.isEmpty() ? "" : ", " + date) + "] (" + f.id() + ")";
    }

    /** The year a claim names in its title, else in its first sentence; null when it names none. */
    /** "## Analysis": the latest report's judgments, conjectures and implications and the subject's forecast ledger, apart from the facts; "" when there is none. */
    static String analysisMarkdown(LibraryStore store, Gathered d) throws IOException { return analysisMarkdown(store, d, Translate.AS_IS); }

    static String analysisMarkdown(LibraryStore store, Gathered d, Translate.Words w) throws IOException {
        Investigation latest = null; String storyTitle = "The story of " + d.label();
        for (Investigation inv : d.reports()) if (!inv.title().equals(storyTitle)) latest = inv;
        String body = latest == null ? "" : latest.body();
        String judgments = Analysis.section(body, "Judgments"), implications = Analysis.section(body, "Implications and options"), summary = Analysis.section(body, "Source summary");
        if (summary.isEmpty()) summary = Analysis.sourceSummary(body);   // the writer sometimes skips it; the library writes one from the references and the checks
        List<Analysis.Conjecture> conj = Analysis.conjectures(body); List<Forecasts.Entry> ledger = Forecasts.of(store, d.label());
        if (judgments.isEmpty() && conj.isEmpty() && ledger.isEmpty() && implications.isEmpty()) return "";
        StringBuilder b = new StringBuilder("## Analysis\n\nApart from the facts" + (latest == null ? "" : ", from " + latest.id()) + ": what they add up to, what the record suggests, what to expect. Never claims on the shelves.\n\n");
        if (!judgments.isEmpty()) { b.append("### Judgments\n\n").append(w.tr(judgments)).append("\n\n"); String src = sourcesLine(judgments, latest); if (!src.isEmpty()) b.append(src).append("\n\n"); }
        if (!conj.isEmpty()) {
            b.append("### Conjectures\n\n");
            for (Analysis.Conjecture c : conj) b.append("- ").append(w.tr(c.text())).append(c.likelihood().isEmpty() ? "" : " — " + c.likelihood() + (c.percent() == null ? "" : " (" + c.percent() + "%)"))
                    .append(c.confidence().isEmpty() ? "" : ", confidence " + c.confidence()).append(c.indicators().isEmpty() ? "" : "; would change this: " + w.tr(c.indicators())).append('\n');
            b.append('\n');
        }
        if (!ledger.isEmpty()) {
            b.append("### Forecasts\n\n");
            for (Forecasts.Entry f : ledger) b.append("- ").append(f.id()).append(" — by ").append(f.by().isEmpty() ? "no date" : f.by()).append(": ").append(w.tr(f.text()))
                    .append(f.likelihood().isEmpty() ? "" : " — " + f.likelihood() + (f.percent() == null ? "" : " (" + f.percent() + "%)")).append(" — ").append(f.outcome() == null || f.outcome().isEmpty() ? "open" : f.outcome()).append('\n');
            Double brier = Forecasts.brier(ledger);
            if (brier != null) b.append("\nBrier score over the scored forecasts: ").append(String.format(Locale.ROOT, "%.3f", brier)).append(" (0 is perfect, 0.25 is coin-tossing).\n");
            b.append('\n');
        }
        if (!implications.isEmpty()) b.append("### Implications and options\n\n").append(w.tr(implications)).append("\n\n");
        if (!summary.isEmpty()) b.append("### Source summary\n\n").append(w.tr(summary)).append("\n\n");
        return b.toString();
    }

    /** The facets the hub shows from the reports, each with the section names that carry it (an organisation's, then a person's): the page's heading, the sections to read. */
    static final String[][] FACETS = {
            {"Customers, peers and alternatives", "Customers, peers and alternatives|Customers and competitors"}, {"Power and governance", "Power and governance"}, {"People", "People"}, {"Influences", "Influences"},
            {"Money", "Money|Holdings and filings"}, {"Places", "Footprint"}, {"Signals", "Workforce and signals"}};

    /** What the subject does, from the latest report that says: a person's "What they do", an organisation's "The work", else a person's "Material"; "" when no report has one. The hub says what they do whatever the latest question leaned on. */
    static String work(List<Investigation> reports) { return latestSection(reports, "What they do", "The work", "Material"); }

    /** The latest report's text under the first of these section names it carries; "" when no report has any. The files are read in order, so the latest that says comes last. */
    static String latestSection(List<Investigation> reports, String... names) {
        Investigation inv = reportOf(reports, names);
        if (inv == null) return "";
        for (String name : names) { String s = TimelineClaims.section(inv.body(), name); if (!s.isEmpty()) return s; }
        return "";
    }

    /** The latest report that carries one of these sections; null when none does. Its reference list is what the section's [n] cite. */
    static Investigation reportOf(List<Investigation> reports, String... names) {
        Investigation out = null;
        for (Investigation inv : reports) {
            if (inv.title().startsWith("The story of ")) continue;
            for (String name : names) if (!TimelineClaims.section(inv.body(), name).isEmpty()) { out = inv; break; }
        }
        return out;
    }

    static final Pattern REF_ENTRY = Pattern.compile("(?m)^\\[(\\d+)\\]\\s+(.*)$");
    /** A report's references by number: {url, title}, from its References list, else its Sources list; a reference with no address has "" for one. */
    static Map<String, String[]> refsOf(String body) {
        Map<String, String[]> out = new LinkedHashMap<>();
        for (String list : new String[]{"References", "Sources"}) {
            Matcher m = REF_ENTRY.matcher(TimelineClaims.section(body, list));
            while (m.find()) {
                if (out.containsKey(m.group(1))) continue;
                String rest = m.group(2).strip();
                Matcher u = Pattern.compile("(?:https?://|file://|raw/)\\S+").matcher(rest);
                String url = u.find() ? u.group().replaceAll("[),.;]+$", "") : "";
                String title = rest.contains(" — ") ? rest.substring(0, rest.indexOf(" — ")).strip() : rest.startsWith("http") ? "" : rest.replaceAll("\\s*\\[(?:public record|own publication|third-party|supplied|told|canon)\\].*$", "").strip();
                out.put(m.group(1), new String[]{url, title});
            }
        }
        return out;
    }

    /** "Sources: [19] https://…; [43] https://…" for the numbers a text cites, from the report it came from; "" when it cites none the report lists. */
    static String sourcesLine(String text, Investigation report) {
        if (text == null || report == null) return "";
        Map<String, String[]> refs = refsOf(report.body());
        List<String> parts = new ArrayList<>(); Matcher c = Pattern.compile("\\[(\\d+)\\]").matcher(text);
        while (c.find()) {
            String n = c.group(1); String[] r = refs.get(n);
            if (r == null) continue;
            String part = "[" + n + "] " + (r[0].isEmpty() ? r[1] : r[0]);
            if (!parts.contains(part)) parts.add(part);
        }
        return parts.isEmpty() ? "" : "Sources: " + String.join("; ", parts);
    }

    /** The decision claims among a subject's: the ones the Timeline step read from a Decisions section, in the order of their years. */
    static List<Finding> decisions(List<Finding> claims) { return ofKind(claims, TimelineClaims.DECISION); }
    /** The statements in the subject's own words, read from a Statements section. */
    static List<Finding> statements(List<Finding> claims) { return ofKind(claims, TimelineClaims.SAID); }
    /** The media records — photographs, video, scans with their provenance — read from a Media section. */
    static List<Finding> media(List<Finding> claims) { return ofKind(claims, TimelineClaims.MEDIA); }
    static List<Finding> ofKind(List<Finding> claims, String mark) {
        List<Finding> out = new ArrayList<>();
        for (Finding f : claims) if (f.state() != Finding.State.retired && (f.title().contains(": " + mark) || f.body().startsWith(mark))) out.add(f);
        out.sort((a, c) -> { String ya = yearOf(a), yc = yearOf(c); return (ya == null ? "" : ya).compareTo(yc == null ? "" : yc); });
        return out;
    }

    static String yearOf(Finding f) {
        Matcher m = YEAR.matcher(f.title());
        if (m.find()) return m.group(1);
        String first = f.body().strip(); int dot = first.indexOf(". "); if (dot > 0) first = first.substring(0, dot + 1);
        m = YEAR.matcher(first);
        return m.find() ? m.group(1) : null;
    }

    /** The report's answer, clipped: the reviewed Answer, else the Reading a dossier opens with, else the answer as submitted, else the first section after the question. */
    static String answerOf(String body, int max) {
        String text = null;
        for (String want : new String[]{"\n## Answer\n", "\n## Reading\n", "\n## Answer (", null}) {
            int at = want == null ? -1 : body.indexOf(want);
            if (at < 0) { if (want != null) continue; int q = body.indexOf("\n## Question\n"); int after = q < 0 ? -1 : body.indexOf("\n## ", q + 1); at = after < 0 ? -1 : after; if (at < 0) { text = body; break; } }
            int line = body.indexOf('\n', at + 1);
            text = line < 0 ? "" : body.substring(line + 1);
            break;
        }
        int next = text.indexOf("\n## "); if (next > 0) text = text.substring(0, next);
        text = text.strip().replace("\n", " ");
        return text.length() > max ? text.substring(0, max).strip() + "…" : text;
    }

    static List<String> notFoundLines(String body) {
        List<String> out = new ArrayList<>();
        int at = body.indexOf("### Searched and not found");
        if (at < 0) return out;
        String rest = body.substring(at); int end = rest.indexOf("\n## "); if (end > 0) rest = rest.substring(0, end);
        for (String l : rest.split("\n")) if (l.startsWith("- ")) out.add(l.substring(2).strip());
        return out;
    }
}
