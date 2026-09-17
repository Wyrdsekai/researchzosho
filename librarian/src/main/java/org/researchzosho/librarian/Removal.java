package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Taking a report or a claim out of the library for good. Retiring keeps a claim on disk and out of
 * the push; removing deletes the file, drops it from the index, and writes a "removed" line to the
 * changes log so a patron who cited the id can learn it is gone.
 *
 * <p>A report's claims are the ones its head lists and the ones whose sources say they came from it.
 * A claim another report also cites is not this report's alone: it stays, and the plan says so. What
 * a run fetched from the web stays on the shelves in every case — a page is source material, not the
 * report's property, and another claim may rest on it.
 */
public final class Removal {

    /** What to remove of a report: the report and its claims, the report alone, or the claims alone. */
    public enum What { all, report, claims }

    /** What a removal would do, before it does it. */
    public record Plan(String id, String kind, String title, What what, boolean reportGoes, List<String> claimsGo, List<String> claimsStay, List<String> titles) {
        public int count() { return claimsGo.size() + (reportGoes ? 1 : 0); }
    }

    /** What a removal did. */
    public record Done(Plan plan, List<String> removed) { }

    private Removal() { }

    /** The plan for an id: an investigation (I-…) with {@code what}, or one claim (F-…). Throws when there is no such entry. */
    public static Plan plan(LibraryStore store, String id, What what) throws IOException {
        if (!LibraryStore.safeName(id)) throw new IOException("no entry " + id);
        Investigation inv = store.investigation(id);
        if (inv != null) {
            Set<String> mine = new LinkedHashSet<>(inv.findings());
            List<Finding> all = store.scanFindings().findings();
            for (Finding f : all) if (citesRun(f, inv.id())) mine.add(f.id());
            List<String> go = new ArrayList<>(), stay = new ArrayList<>(), titles = new ArrayList<>();
            for (String fid : mine) {
                Finding f = store.finding(fid);
                if (f == null) continue;
                if (what == What.report || citedByAnother(store, f, inv.id())) stay.add(fid);
                else { go.add(fid); titles.add(f.title()); }
            }
            return new Plan(inv.id(), "investigation", inv.title(), what, what != What.claims, go, stay, titles);
        }
        Finding f = store.finding(id);
        if (f != null) return new Plan(f.id(), "finding", f.title(), What.all, false, List.of(f.id()), List.of(), List.of(f.title()));
        throw new IOException("no report or claim " + id);
    }

    /** Whether a claim's sources say it came out of a run. */
    static boolean citesRun(Finding f, String invId) {
        for (Finding.Source s : f.sources()) { Matcher m = Cataloger.FROM_RUN.matcher(s.whyItMatters()); while (m.find()) if (m.group(1).equals(invId)) return true; }
        return false;
    }

    /** Whether some other report lists the claim or the claim's sources name some other run. */
    static boolean citedByAnother(LibraryStore store, Finding f, String invId) throws IOException {
        for (Finding.Source s : f.sources()) { Matcher m = Cataloger.FROM_RUN.matcher(s.whyItMatters()); while (m.find()) if (!m.group(1).equals(invId) && store.investigation(m.group(1)) != null) return true; }
        if (Files.isDirectory(store.investigationsDir())) {
            try (var files = Files.list(store.investigationsDir())) {
                for (Path p : (Iterable<Path>) files::iterator) {
                    String other = p.getFileName().toString().replaceFirst("\\.md$", "");
                    if (other.equals(invId)) continue;
                    Investigation o = store.investigation(other);
                    if (o != null && o.findings().contains(f.id())) return true;
                }
            }
        }
        return false;
    }

    /** Carry the plan out: files deleted, the index told, the changes log written, the report's list of claims pruned when it stays. */
    public static Done apply(LibraryStore store, Plan plan, String who) throws IOException {
        List<String> removed = new ArrayList<>();
        LibrarianIndex index = new LibrarianIndex(store);
        for (String fid : plan.claimsGo()) {
            Path p = LibraryStore.under(store.findingsDir(), fid + ".md");
            if (p == null || !Files.exists(p)) continue;
            Files.delete(p);
            try { index.remove(fid); } catch (IOException ignored) { }
            Changes.append(store, "finding", fid, "removed", "by " + who + (plan.kind().equals("investigation") ? ", with " + plan.id() : ""));
            removed.add(fid);
        }
        if (plan.reportGoes()) {
            Path p = LibraryStore.under(store.investigationsDir(), plan.id() + ".md");
            if (p != null && Files.exists(p)) {
                Files.delete(p);
                try { index.remove(plan.id()); } catch (IOException ignored) { }
                Changes.append(store, "investigation", plan.id(), "removed", "by " + who + (plan.claimsGo().isEmpty() ? "" : ", with " + plan.claimsGo().size() + " claim(s)"));
                removed.add(plan.id());
            }
        } else if (plan.kind().equals("investigation") && !plan.claimsGo().isEmpty()) {
            Investigation inv = store.investigation(plan.id());
            if (inv != null) {
                List<String> left = new ArrayList<>(inv.findings()); left.removeAll(plan.claimsGo());
                store.write(new Investigation(inv.id(), inv.title(), inv.state(), inv.writer(), inv.recordedAt(), left, inv.open(), inv.body()));
            }
        }
        store.circulate("remove", who + " :: " + plan.id() + " (" + plan.what() + ") — " + removed.size() + " gone" + (plan.claimsStay().isEmpty() ? "" : ", " + plan.claimsStay().size() + " claim(s) kept: cited elsewhere"));
        return new Done(plan, removed);
    }

    /** The plan in words, for the terminal and the page. */
    public static String describe(Plan p) {
        StringBuilder sb = new StringBuilder();
        if (p.kind().equals("finding")) return "remove the claim " + p.id() + " — " + p.title() + " (the file is deleted; retire keeps it)";
        sb.append(p.reportGoes() ? "remove the report " : "keep the report ").append(p.id()).append(" — ").append(Acquisitions.compress(p.title(), 90)).append('\n');
        if (p.what() == What.report) sb.append("keep its ").append(p.claimsStay().size()).append(" claim(s)\n");
        else {
            sb.append("remove ").append(p.claimsGo().size()).append(" claim(s) of its own:\n");
            for (int i = 0; i < p.claimsGo().size(); i++) sb.append("  ").append(p.claimsGo().get(i)).append(" — ").append(Acquisitions.compress(p.titles().get(i), 80)).append('\n');
            if (!p.claimsStay().isEmpty()) sb.append("keep ").append(p.claimsStay().size()).append(" claim(s) another report also cites: ").append(String.join(", ", p.claimsStay())).append('\n');
        }
        sb.append("what the run fetched from the web stays on the shelves");
        return sb.toString();
    }
}
