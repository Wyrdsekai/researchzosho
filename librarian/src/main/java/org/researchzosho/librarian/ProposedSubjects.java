package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The subjects the cataloger proposes ({@code catalog/subjects.proposed.md}): listed with a number each, accepted into the subject list
 * or dropped, by number, by slug or all at once. One code for the command ({@code researchzosho subjects proposed|accept|drop}), the chat
 * ({@code /subjects}) and the Subjects page, which until 2026-11-07 showed the list and could not decide it.
 */
final class ProposedSubjects {
    private ProposedSubjects() { }

    record Proposal(String slug, String description) { }

    /** The file's lines above the proposals, kept as they are, and the proposals. */
    record Sheet(List<String> head, List<Proposal> proposed) { }

    static Path file(LibraryStore store) { return store.subjectsFile().resolveSibling("subjects.proposed.md"); }

    static Sheet read(LibraryStore store) throws IOException {
        List<Proposal> proposed = new ArrayList<>();
        List<String> head = new ArrayList<>();
        Path f = file(store);
        if (Files.exists(f)) for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            if (line.startsWith("- ")) { int dash = line.indexOf(" — "); proposed.add(dash > 0 ? new Proposal(line.substring(2, dash).strip(), line.substring(dash + 3).strip()) : new Proposal(line.substring(2).strip(), "")); }
            else if (proposed.isEmpty()) head.add(line);
        }
        return new Sheet(head, proposed);
    }

    static List<Proposal> list(LibraryStore store) throws IOException { return read(store).proposed(); }

    /** The proposals, numbered, one a line; a sentence when there are none. */
    static String text(List<Proposal> proposed) {
        if (proposed.isEmpty()) return "No subjects are proposed.";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < proposed.size(); i++) b.append(b.isEmpty() ? "" : "\n").append(i + 1).append(". ").append(proposed.get(i).slug()).append(proposed.get(i).description().isEmpty() ? "" : " — " + Acquisitions.compress(proposed.get(i).description(), 90));
        return b.toString();
    }

    /** What was decided: the proposals taken (accepted or dropped), and what the person named that was no proposal. */
    record Decided(String op, List<Proposal> taken, List<String> unknown) {
        String text() {
            StringBuilder b = new StringBuilder();
            for (Proposal p : taken) b.append(b.isEmpty() ? "" : "\n").append(op.equals("accept") ? "Accepted " : "Dropped ").append(p.slug()).append(p.description().isEmpty() || !op.equals("accept") ? "." : " — " + p.description() + ".");
            for (String u : unknown) b.append(b.isEmpty() ? "" : "\n").append("Not a proposal: ").append(u).append(".");
            if (op.equals("accept") && !taken.isEmpty()) b.append("\n").append(taken.size() == 1 ? "Now researchzosho settle files the claims under it." : "Now researchzosho settle files the claims under them.");
            if (taken.isEmpty() && unknown.isEmpty()) b.append("Say which: numbers from the list, slugs, or all.");
            return b.toString();
        }
    }

    /**
     * Accept or drop the proposals named: numbers from the list (from 1), slugs, or "all". An accepted one joins the subject list with its
     * description; a dropped one is forgotten; the file keeps the rest. Nothing happens for a name that is no proposal, and it is said.
     */
    static Decided decide(LibraryStore store, String op, List<String> which) throws IOException {
        if (!op.equals("accept") && !op.equals("drop")) throw new IllegalArgumentException("accept or drop");
        Sheet sheet = read(store);
        List<Proposal> proposed = sheet.proposed();
        Set<String> chosen = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String a : which) {
            if (a.equals("all")) { for (Proposal p : proposed) chosen.add(p.slug()); continue; }
            if (a.matches("\\d+")) { int n = Integer.parseInt(a); if (n >= 1 && n <= proposed.size()) chosen.add(proposed.get(n - 1).slug()); else unknown.add(a); continue; }
            if (proposed.stream().anyMatch(p -> p.slug().equals(a))) chosen.add(a); else unknown.add(a);
        }
        List<Proposal> taken = new ArrayList<>();
        for (Proposal p : proposed) {
            if (!chosen.contains(p.slug())) continue;
            if (op.equals("accept")) Cataloger.addToVocabulary(store, p.slug(), p.description());
            taken.add(p);
        }
        if (!taken.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String h : sheet.head()) sb.append(h).append('\n');
            for (Proposal p : proposed) if (!chosen.contains(p.slug())) sb.append("- ").append(p.slug()).append(" — ").append(p.description()).append('\n');
            Files.writeString(file(store), sb.toString(), StandardCharsets.UTF_8);
        }
        return new Decided(op, taken, unknown);
    }
}
