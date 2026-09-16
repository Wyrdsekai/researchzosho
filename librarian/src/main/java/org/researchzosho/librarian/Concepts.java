package org.researchzosho.librarian;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The second kind of edge on the map. A triple says what a claim asserts (subject, predicate, a measured value);
 * it seldom names the concepts the claim rests on — temperature, viscosity, friction, a method. Those are where
 * two distant areas can meet, so each claim gets a note listing them, and the graph draws a "mentions" edge from
 * the claim's subject to each concept. The model reads the claim once; the note stops the nightly re-ask.
 */
public final class Concepts {

    static final int PER_NIGHT = org.researchzosho.Config.getInt("RESEARCHZOSHO_CONCEPTS_PER_NIGHT", 40);
    static final int MAX_PER_CLAIM = 8;
    public static final String NOTE = "concepts";

    /** Claim text → the concepts, one per line; NONE when there are none. A test scripts it. */
    public interface Extractor { String extract(String claim) throws Exception; }

    private Concepts() { }

    public static Extractor driveExtractor(org.researchzosho.drive.DriveClient drive) {
        return claim -> {
            var msgs = new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode();
            msgs.addObject().put("role", "user").put("content",
                    "List the general concepts this claim rests on: the mechanisms, quantities, materials, methods and phenomena "
                    + "it involves, as they would be named in any field. Short noun phrases, one per line, lowercase, no numbers, "
                    + "no bullets, three to " + MAX_PER_CLAIM + " lines. Leave out the claim's own subject and anything that only "
                    + "this field would say; keep what another field might also speak of — a quantity, a material, a capacity of "
                    + "the people involved, a way the work is organised, a living agent, a structure. Write NONE when there is nothing of the kind.\n\nCLAIM:\n"
                    + Fence.wrap("CLAIM", claim) + "\n" + Fence.rule("CLAIM"));
            return drive.classify(msgs, 200);
        };
    }

    /** The concepts on a finding's note, or none. */
    public static List<String> of(Finding f) {
        for (Finding.Note n : f.notes()) if (NOTE.equals(n.kind())) return parse(n.text());
        return List.of();
    }

    static List<String> parse(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank() || text.strip().equalsIgnoreCase("none")) return out;
        for (String c : text.split(";")) { String x = c.strip(); if (!x.isEmpty() && !out.contains(x)) out.add(x); }
        return out;
    }

    /** The model's reply as concepts: one per line, cleaned, at most MAX_PER_CLAIM, never the claim's own subject. */
    static List<String> clean(String raw, Finding f) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) return List.of();
        String own = f.triple() == null ? "" : f.triple().subject().toLowerCase(Locale.ROOT);
        for (String line : raw.split("\n")) {
            String c = line.strip().replaceAll("^[-*•\\d.)\\s]+", "").replaceAll("[.;:]+$", "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (c.isEmpty() || c.equals("none") || c.length() > 60 || c.split(" ").length > 4 || c.contains("|")) continue;
            if (!own.isEmpty() && (own.contains(c) || c.contains(own))) continue;
            if (Bridges.STOP.contains(c)) continue;   // "consensus", "criteria", "limitations": how a field talks about itself, not what a claim rests on
            out.add(c);
            if (out.size() >= MAX_PER_CLAIM) break;
        }
        return new ArrayList<>(out);
    }

    public record Outcome(int asked, int filled) { }

    /** Every finding without a concepts note, up to {@code budget}: ask once, note the answer (NONE included). */
    public static Outcome fill(LibraryStore store, Extractor extractor, int budget) throws IOException {
        int asked = 0, filled = 0;
        for (Finding f : store.scanFindings().findings()) {
            if (asked >= budget) break;
            if (f.state() == Finding.State.retired) continue;
            if (f.notes().stream().anyMatch(n -> NOTE.equals(n.kind()))) continue;
            asked++;
            List<String> cs = List.of();
            try { cs = clean(extractor.extract(f.title() + "\n\n" + f.body().strip()), f); } catch (Exception e) { /* asked and no answer; the note stops the re-ask */ }
            store.write(f.withNote(new Finding.Note(NOTE, "crew:concepts", java.time.LocalDate.now().toString(), cs.isEmpty() ? "NONE" : String.join("; ", cs))));
            if (!cs.isEmpty()) filled++;
        }
        store.circulate("concepts", asked + " asked, " + filled + " filled");
        return new Outcome(asked, filled);
    }
}
