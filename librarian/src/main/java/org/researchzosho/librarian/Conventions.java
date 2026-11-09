package org.researchzosho.librarian;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The conventions this build knows, read off the fields that carry them ({@link Profile#conventionName}): how a claim must be stated
 * in a subject, whatever the goal. Several may apply to one question: a home question that meets a zoning dispute carries law.
 */
public final class Conventions {

    private Conventions() { }

    record Spec(String name, String words, String claimRules) { }

    static final List<Spec> SPECS = List.of(
            new Spec("law",
                    "Carried when a claim is about what a law, a regulation, a court or an ordinance requires or allows, under any goal.",
                    "Every claim of law carries its jurisdiction and the date the law stood, cites the primary text, the statute, the regulation, the decision or the ordinance, by its own citation, and says when it is a secondary source's reading rather than the text; a claim that has changed since says what changed and when."),
            new Spec("science",
                    "Carried when a claim rests on the scientific record: a paper, a preprint, a dataset, under any goal.",
                    "Every scientific claim cites the record by DOI or the office's identifier, names the preprint version when the paper is one, names the dataset when the claim rests on data, and is written so a BibTeX entry can be made from its source."),
            new Spec("patents",
                    "Carried when a claim is about a patent or an application: its number, claims, dates, assignee, family or status, under any goal.",
                    "Every patent claim carries the patent's number and office, the claims it rests on quoted from the record, the filing and grant dates, the assignee, the family members and the status as the office records them."));

    static final class ProfileConvention implements Convention {
        private final Spec spec; private final List<Profile> profiles;
        ProfileConvention(Spec spec, List<Profile> profiles) { this.spec = spec; this.profiles = List.copyOf(profiles); }
        @Override public String name() { return spec.name(); }
        @Override public String words() { return spec.words(); }
        @Override public String claimRules() { return spec.claimRules(); }
        @Override public List<Profile> profiles() { return profiles; }
        @Override public Goal.Applies appliesTo(String question) {
            for (Profile p : profiles) if (p.applies(question == null ? "" : question)) return Goal.Applies.yes("the question's words are " + p.name() + "'s, so its claims carry the " + spec.name() + " convention");
            return Goal.Applies.no("nothing in the question is of " + spec.name());
        }
    }

    private static volatile List<Convention> known;

    /** One of each convention this build knows. */
    public static List<Convention> known() {
        List<Convention> k = known;
        if (k == null) {
            Map<String, List<Profile>> backing = new LinkedHashMap<>();
            for (Profile p : Profiles.known()) if (!p.conventionName().isBlank()) backing.computeIfAbsent(p.conventionName(), x -> new ArrayList<>()).add(p);
            List<Convention> out = new ArrayList<>();
            for (Spec s : SPECS) out.add(new ProfileConvention(s, backing.getOrDefault(s.name(), List.of())));
            known = k = List.copyOf(out);
        }
        return k;
    }

    public static Convention named(String name) {
        for (Convention c : known()) if (c.name().equalsIgnoreCase(name == null ? "" : name.strip())) return c;
        return null;
    }

    /** The conventions a question's words carry, each with its reason; several at once when the question spans subjects. */
    public static List<Convention> forQuestion(String question) {
        List<Convention> out = new ArrayList<>();
        for (Convention c : known()) if (c.appliesTo(question).yes()) out.add(c);
        return out;
    }
}
