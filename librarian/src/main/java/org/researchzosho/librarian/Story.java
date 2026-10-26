package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.researchzosho.drive.Declined;

/**
 * The story of a subject (0.6.6): a report written chapter by chapter from the claims the library holds on a person or an organisation,
 * for a reader who wants the people and what happened, not a table. Each chapter is a decade of the claims that name a year, with the
 * undated ones first as background; every sentence cites the claim it rests on, and what a person told the library is said to be theirs.
 * Filed as a report, so it is a page to dive into from the dossier, and written again when the shelves have grown. The model writes the
 * prose; the library chooses the claims, the chapters and the citations' form.
 */
public final class Story {
    private static final ObjectMapper M = new ObjectMapper();
    private Story() { }

    /** The model a story is written with: the library's own; tests put a stand-in here. */
    static volatile java.util.function.Supplier<Researcher.Drive> DRIVE = Explain::drive;

    /** The subject's story, when there is one: the latest report titled "The story of <subject>", else null. */
    public static Investigation existing(LibraryStore store, String subject) throws IOException {
        Dossier.Gathered d = Dossier.gather(store, subject);
        Investigation story = null;
        for (Investigation inv : d.reports()) if (inv.title().equals("The story of " + d.label())) story = inv;
        return story;
    }

    /**
     * The story follows the shelves: when a report on a subject lands and the subject already has a story, the story is written again from
     * the grown shelves, the earlier version kept as its own report. Null when the subject has no story yet (the person chooses to have one
     * by writing the first) or no model answers.
     */
    public static ObjectNode refresh(LibraryStore store, String subject) throws IOException {
        if (existing(store, subject) == null) return null;
        Researcher.Drive drive = DRIVE.get();
        if (drive == null) return null;
        return write(store, subject, drive);
    }

    public static ObjectNode write(LibraryStore store, String subject, Researcher.Drive drive) throws IOException {
        if (drive == null) throw ProtocolError.invalidArgs("A story needs a model to write it, and none is answering. Set the drive, then ask again.");
        // the reports' timelines first, so the story is written from every dated event they carry, not only the claims the runs noted
        Dossier.Gathered first = Dossier.gather(store, subject);
        int fromTimelines = 0;
        for (Investigation inv : first.reports()) if (!inv.title().startsWith("The story of ")) fromTimelines += TimelineClaims.file(store, inv, first.label()).size();
        Dossier.Gathered d = fromTimelines == 0 ? first : Dossier.gather(store, subject);
        if (d.claims().isEmpty()) throw ProtocolError.notFound("The library holds nothing on " + subject + " yet. A research run starts it: researchzosho research ask \"Who is " + subject + "?\"");
        Map<String, List<Finding>> decades = new TreeMap<>(); List<Finding> undated = new ArrayList<>();
        for (Finding f : d.claims()) { String y = Dossier.yearOf(f); if (y == null) undated.add(f); else decades.computeIfAbsent(y.substring(0, 3) + "0s", k -> new ArrayList<>()).add(f); }
        Map<String, List<Finding>> eras = chapters(decades);
        String title = "The story of " + d.label();
        StringBuilder body = new StringBuilder("# ").append(title).append("\n\nWritten by the library on ").append(LocalDate.now()).append(" from the ").append(d.claims().size())
                .append(" claims it holds; every sentence cites the claim it rests on, and what a person told the library is said to be theirs. Written again when the shelves have grown.\n\n");
        int chapters = 0;
        if (!undated.isEmpty()) { chapter(drive, d.label(), "Background", undated, body); chapters++; }
        for (var e : eras.entrySet()) { chapter(drive, d.label(), e.getKey(), e.getValue(), body); chapters++; }
        body.append("## Sources\n\n");
        for (Finding f : d.claims()) body.append("- [").append(f.id()).append("] ").append(f.title()).append(" (").append(SourceClass.of(f.sources()).label()).append(")\n");
        String id = store.nextInvestigationId(title);
        Investigation inv = new Investigation(id, title, Finding.State.accepted, "librarian:story", Instant.now().toString(), d.claims().stream().map(Finding::id).toList(), List.of(), body.toString());   // a view over the claims: nothing in it to review
        store.write(inv);
        new LibrarianIndex(store).upsert(inv);
        store.circulate("story", id + " on " + d.label() + ", " + chapters + " chapters from " + d.claims().size() + " claims");
        ObjectNode r = M.createObjectNode();
        r.put("id", id); r.put("subject", d.label()); r.put("chapters", chapters); r.put("claims", d.claims().size()); r.put("from_timelines", fromTimelines); r.put("markdown", body.toString());
        return r;
    }

    /** How many claims a decade needs to be a chapter of its own; a thinner one joins the next (a chapter on one claim, a birth, narrated the thinness, 2026-10-23). */
    static final int CHAPTER_CLAIMS = 3;

    /** The decades folded into chapters: a run of thin decades joins the first decade with enough, named by its span ("1950s–1990s"); a thin tail joins the chapter before it. */
    static Map<String, List<Finding>> chapters(Map<String, List<Finding>> decades) {
        Map<String, List<Finding>> out = new java.util.LinkedHashMap<>();
        String from = null; List<Finding> pending = new ArrayList<>(); String lastKey = null;
        for (var e : decades.entrySet()) {
            if (from == null) from = e.getKey();
            pending.addAll(e.getValue());
            if (pending.size() >= CHAPTER_CLAIMS) { lastKey = from.equals(e.getKey()) ? from : from + "–" + e.getKey(); out.put(lastKey, pending); pending = new ArrayList<>(); from = null; }
        }
        if (!pending.isEmpty()) {
            String last = decades.keySet().stream().reduce((a, b) -> b).orElse(from);
            if (lastKey == null) out.put(from.equals(last) ? from : from + "–" + last, pending);
            else { List<Finding> prev = out.remove(lastKey); prev.addAll(pending); out.put(lastKey.split("–")[0] + "–" + last, prev); }
        }
        return out;
    }

    static void chapter(Researcher.Drive drive, String subject, String era, List<Finding> claims, StringBuilder body) {
        ArrayNode messages = M.createArrayNode();
        messages.addObject().put("role", "system").put("content", "You write one chapter of the story of " + subject + ", the " + era + ", for a reader who wants the people and what "
                + "happened. Write from the claims below and from nothing else; cite each claim you use by its id in square brackets right after the sentence it supports, like "
                + "[F-0012-x]. A claim marked told is a person's own account: say whose it is, and keep their words where they are given. Where the claims disagree or leave a "
                + "gap, say so in a sentence. A dated event that is only a title, with no verb, is a work of the subject's that appeared that year, unless "
                + "another claim says otherwise. Write what the claims say and nothing about what they do not cover. 120 to 250 words of plain prose, "
                + "no heading, no list.");
        StringBuilder u = new StringBuilder("The claims:\n");
        for (Finding f : claims) {
            SourceClass c = SourceClass.of(f.sources());
            u.append("- [").append(f.id()).append("] ").append(f.title()).append(" — ").append(f.body().strip().replace("\n", " ")).append(" (").append(c.label());
            if (c == SourceClass.told) u.append(", told by ").append(f.writer().replaceFirst("^patron:", ""));
            if (f.validAsOf() != null && !f.validAsOf().isEmpty()) u.append(", ").append(f.validAsOf());
            u.append(")\n");
        }
        messages.addObject().put("role", "user").put("content", u.toString());
        String text;
        try (var step = Declines.step("write the " + era + " chapter of the story of " + subject)) { text = drive.prose(messages, 700); }
        catch (Declined x) { text = "The model declined to write this chapter: " + x.getMessage(); }
        if (text == null || text.isBlank()) text = "The model wrote nothing for this chapter.";
        body.append("## ").append(era).append("\n\n").append(text.strip()).append("\n\n");
    }
}
