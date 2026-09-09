package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GEDCOM in and out, for the genealogy profile. Import turns INDI records into person nodes and
 * FAM records plus the events into DRAFT findings — one edge each, with the GEDCOM file as the source
 * (a file the person shelved: the personal tier) — so a tree arrives on the same footing as any other
 * claim: reviewable, disputable, cited. Nothing is accepted by import. A person with no death and a
 * birth within the living window (or unknown) is marked private unless the import says otherwise.
 * Export walks the person subgraph and writes GEDCOM 5.5.1 for anything else to read, skipping private
 * nodes unless asked.
 */
public final class Gedcom {

    static final int LIVING_YEARS = 110;

    public record Outcome(int persons, int families, int findings, int privateNodes, List<String> problems) { }

    private record Indi(String xref, String name, String sex, Map<String, String[]> events, List<String> famc, List<String> fams) { }
    private record Fam(String xref, String husb, String wife, List<String> chil, String[] marr) { }

    private Gedcom() { }

    public static Outcome importFile(LibraryStore store, Path file, boolean includeLiving) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        Map<String, Indi> indis = new LinkedHashMap<>();
        Map<String, Fam> fams = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        // parse level-0 records
        String xref = null, type = null;
        Indi indi = null; Fam fam = null;
        String eventTag = null; String[] event = null;
        for (String raw : lines) {
            String line = raw.strip().replace("﻿", "");
            if (line.isEmpty()) continue;
            String[] p = line.split(" ", 3);
            int level;
            try { level = Integer.parseInt(p[0]); } catch (NumberFormatException e) { continue; }
            if (level == 0) {
                if (indi != null) indis.put(indi.xref(), indi);
                if (fam != null) fams.put(fam.xref(), fam);
                indi = null; fam = null; eventTag = null; event = null;
                if (p.length >= 3 && p[1].startsWith("@")) { xref = p[1]; type = p[2]; }
                else { xref = null; type = p.length > 1 ? p[1] : ""; }
                if ("INDI".equals(type)) indi = new Indi(xref, "", "", new LinkedHashMap<>(), new ArrayList<>(), new ArrayList<>());
                if ("FAM".equals(type)) fam = new Fam(xref, "", "", new ArrayList<>(), new String[]{"", ""});
                continue;
            }
            String tag = p.length > 1 ? p[1] : "";
            String val = p.length > 2 ? p[2] : "";
            if (indi != null) {
                if (level == 1) {
                    eventTag = null; event = null;
                    switch (tag) {
                        case "NAME" -> indi = new Indi(indi.xref(), val.replace("/", "").replaceAll("\\s+", " ").strip(), indi.sex(), indi.events(), indi.famc(), indi.fams());
                        case "SEX" -> indi = new Indi(indi.xref(), indi.name(), val, indi.events(), indi.famc(), indi.fams());
                        case "FAMC" -> indi.famc().add(val);
                        case "FAMS" -> indi.fams().add(val);
                        case "BIRT", "DEAT", "BURI", "RESI", "IMMI", "EMIG", "OCCU" -> { eventTag = tag; event = new String[]{"", tag.equals("OCCU") ? val : ""}; indi.events().put(tag + "#" + indi.events().size(), event); }
                        default -> { }
                    }
                } else if (level == 2 && event != null) {
                    if ("DATE".equals(tag)) event[0] = val;
                    if ("PLAC".equals(tag)) event[1] = val;
                }
            } else if (fam != null) {
                if (level == 1) {
                    eventTag = null; event = null;
                    switch (tag) {
                        case "HUSB" -> fam = new Fam(fam.xref(), val, fam.wife(), fam.chil(), fam.marr());
                        case "WIFE" -> fam = new Fam(fam.xref(), fam.husb(), val, fam.chil(), fam.marr());
                        case "CHIL" -> fam.chil().add(val);
                        case "MARR" -> { eventTag = tag; event = fam.marr(); }
                        default -> { }
                    }
                } else if (level == 2 && event != null) {
                    if ("DATE".equals(tag)) event[0] = val;
                    if ("PLAC".equals(tag)) event[1] = val;
                }
            }
        }
        if (indi != null) indis.put(indi.xref(), indi);
        if (fam != null) fams.put(fam.xref(), fam);

        // nodes: every person, kind person, private when living
        String source = "file://" + file.toAbsolutePath().normalize();
        int privateCount = 0, findings = 0;
        for (Indi i : indis.values()) {
            if (i.name().isBlank()) { problems.add(i.xref() + " has no name; skipped"); continue; }
            boolean living = !includeLiving && isLiving(i);
            Graph.setKind(store, i.name(), "person", living);
            if (living) privateCount++;
            for (Map.Entry<String, String[]> e : i.events().entrySet()) {
                String tag = e.getKey().substring(0, e.getKey().indexOf('#'));
                String date = e.getValue()[0], place = e.getValue()[1];
                String pred = switch (tag) { case "BIRT" -> "born-in"; case "DEAT" -> "died-in"; case "BURI" -> "buried-in"; case "RESI" -> "lived-in"; case "IMMI", "EMIG" -> "migrated-to"; case "OCCU" -> "occupation"; default -> null; };
                if (pred == null || place.isBlank()) continue;
                if (!tag.equals("OCCU")) Graph.setKind(store, place, "place", false);
                findings += edge(store, i.name(), pred, place, date, source, i.xref() + " " + tag) ? 1 : 0;
            }
        }
        for (Fam f : fams.values()) {
            String h = name(indis, f.husb()), w = name(indis, f.wife());
            if (!h.isEmpty() && !w.isEmpty()) findings += edge(store, h, "married-to", w, f.marr()[0], source, f.xref() + " MARR" + (f.marr()[1].isBlank() ? "" : " at " + f.marr()[1])) ? 1 : 0;
            for (String c : f.chil()) {
                String child = name(indis, c);
                if (child.isEmpty()) continue;
                if (!h.isEmpty()) findings += edge(store, h, "parent-of", child, "", source, f.xref() + " CHIL") ? 1 : 0;
                if (!w.isEmpty()) findings += edge(store, w, "parent-of", child, "", source, f.xref() + " CHIL") ? 1 : 0;
            }
        }
        store.circulate("gedcom-import", indis.size() + " persons, " + fams.size() + " families, " + findings + " draft findings from " + file.getFileName());
        return new Outcome(indis.size(), fams.size(), findings, privateCount, problems);
    }

    private static String name(Map<String, Indi> indis, String xref) {
        Indi i = indis.get(xref);
        return i == null ? "" : i.name();
    }

    static boolean isLiving(Indi i) {
        boolean dead = i.events().keySet().stream().anyMatch(k -> k.startsWith("DEAT") || k.startsWith("BURI"));
        if (dead) return false;
        String birth = i.events().entrySet().stream().filter(e -> e.getKey().startsWith("BIRT")).map(e -> e.getValue()[0]).findFirst().orElse("");
        Integer y = year(birth);
        return y == null || y > LocalDate.now().getYear() - LIVING_YEARS;
    }

    static Integer year(String date) {
        Matcher m = Pattern.compile("(1[5-9]\\d\\d|20\\d\\d)").matcher(date == null ? "" : date);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    /** One draft finding per edge, deduplicated by its triple against what is already on the shelf. */
    private static boolean edge(LibraryStore store, String subject, String predicate, String object, String date, String source, String why) throws IOException {
        Finding.Triple t = new Finding.Triple(subject, predicate, object);
        for (Finding f : store.scanFindings().findings()) {
            if (f.triple() != null && f.triple().sameKey(t) && Finding.Triple.canon(f.triple().object()).equals(Finding.Triple.canon(t.object()))) return false;
        }
        String claim = subject + " " + predicate.replace('-', ' ') + " " + object + (date.isBlank() ? "" : " (" + date + ")") + ".";
        String id = store.nextFindingId(subject + " " + predicate + " " + object);
        Finding f = new Finding(id, Acquisitions.compress(claim, 80), List.of(), Finding.State.draft, Finding.ClaimType.extraction,
                Finding.Confidence.medium, "gedcom-import", Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "",
                List.of(new Finding.Source(source, "GEDCOM " + why, "the family file the person shelved")), List.of(), null, claim + "\n", t, List.of());
        store.write(f);
        return true;
    }

    /** The person subgraph as GEDCOM 5.5.1: persons reachable through kinship edges from {@code focus}. */
    public static String export(LibraryStore store, String focus, boolean includeLiving) throws IOException {
        Graph g = Graph.build(store);
        Graph.Neighbourhood nb = g.around(focus, 12, 5000, includeLiving);
        StringBuilder sb = new StringBuilder("0 HEAD\n1 SOUR ResearchZosho\n1 GEDC\n2 VERS 5.5.1\n1 CHAR UTF-8\n");
        if (nb.focus() == null) return sb.append("0 TRLR\n").toString();
        Map<String, String> xref = new LinkedHashMap<>();
        int n = 1;
        for (Graph.Node node : nb.nodes()) if (node.kind().equals("person")) xref.put(node.id(), "@I" + (n++) + "@");
        // families: one per married pair, children attached by parent-of edges
        Map<String, List<String>> children = new LinkedHashMap<>();
        List<String[]> couples = new ArrayList<>();
        for (Graph.Edge e : nb.edges()) {
            if (e.predicate().equals("married-to") && xref.containsKey(e.from()) && xref.containsKey(e.to())) couples.add(new String[]{e.from(), e.to()});
            if (e.predicate().equals("parent-of") && xref.containsKey(e.from()) && xref.containsKey(e.to())) children.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to());
        }
        Map<String, String> famOf = new LinkedHashMap<>();
        int fn = 1;
        for (String[] c : couples) { String fx = "@F" + (fn++) + "@"; famOf.put(c[0] + "|" + c[1], fx); }
        for (Graph.Node node : nb.nodes()) {
            if (!xref.containsKey(node.id())) continue;
            sb.append("0 ").append(xref.get(node.id())).append(" INDI\n1 NAME ").append(node.label()).append('\n');
            for (Graph.Edge e : nb.edges()) {
                if (!e.from().equals(node.id())) continue;
                String tag = switch (e.predicate()) { case "born-in" -> "BIRT"; case "died-in" -> "DEAT"; case "buried-in" -> "BURI"; case "lived-in" -> "RESI"; case "migrated-to" -> "IMMI"; case "occupation" -> "OCCU"; default -> null; };
                if (tag == null) continue;
                String place = label(nb, e.to());
                if (tag.equals("OCCU")) { sb.append("1 OCCU ").append(place).append('\n'); continue; }
                sb.append("1 ").append(tag).append('\n');
                Finding f = store.finding(e.findingId());
                if (f != null) { Matcher m = Pattern.compile("\\(([^)]+)\\)\\.\\s*$").matcher(f.body().strip()); if (m.find()) sb.append("2 DATE ").append(m.group(1)).append('\n'); }
                sb.append("2 PLAC ").append(place).append('\n');
            }
            for (Map.Entry<String, String> f : famOf.entrySet()) if (f.getKey().startsWith(node.id() + "|") || f.getKey().endsWith("|" + node.id())) sb.append("1 FAMS ").append(f.getValue()).append('\n');
            for (Map.Entry<String, List<String>> ch : children.entrySet()) if (ch.getValue().contains(node.id())) {
                for (Map.Entry<String, String> f : famOf.entrySet()) if (f.getKey().startsWith(ch.getKey() + "|") || f.getKey().endsWith("|" + ch.getKey())) { sb.append("1 FAMC ").append(f.getValue()).append('\n'); break; }
            }
        }
        for (Map.Entry<String, String> f : famOf.entrySet()) {
            String[] c = f.getKey().split("\\|");
            sb.append("0 ").append(f.getValue()).append(" FAM\n1 HUSB ").append(xref.get(c[0])).append("\n1 WIFE ").append(xref.get(c[1])).append('\n');
            for (String parent : c) for (String kid : children.getOrDefault(parent, List.of())) if (xref.containsKey(kid)) sb.append("1 CHIL ").append(xref.get(kid)).append('\n');
        }
        return sb.append("0 TRLR\n").toString();
    }

    private static String label(Graph.Neighbourhood nb, String id) {
        for (Graph.Node n : nb.nodes()) if (n.id().equals(id)) return n.label();
        return id;
    }
}
