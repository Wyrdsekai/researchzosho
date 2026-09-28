package org.researchzosho.librarian;

import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Genealogy's reading of the graph: who is a person although nobody marked them, and who may be living. In genealogy's own view
 * ({@link #view}) it reads every claim, as the family commands and pages need it; in the core graph it reads genealogy's own work alone
 * (the family's texts and tree files, and the runs somebody asked of genealogy), so an ordinary claim never makes anybody a person.
 */
public final class FamilyPeople {

    private FamilyPeople() { }

    /** A claim of these kinds is about a person: its subject is one. */
    static final Set<String> ABOUT_A_PERSON = Set.of("born-on", "born-in", "died-on", "died-in", "buried-in", "baptised-in", "baptised-on", "aged", "sex");
    /** Facts of a person's life that also fit a firm or a town: their subject is a person only in a claim about a family. */
    static final Set<String> OF_A_LIFE = Set.of("lived-in", "occupation", "migrated-to", "life-event");

    /** A family read in: the folder the family's own files are kept in is made, which is how the library knows it holds family work. */
    static void holdsAFamily(LibraryStore store) throws IOException { Files.createDirectories(store.root().resolve("family")); }

    /** The claims each of genealogy's own views was read from, while the view lives: working out names and families reads them again from here. */
    private static final Map<Graph, List<Finding>> READ = Collections.synchronizedMap(new WeakHashMap<>());

    /** The claims a graph was read from: those genealogy's view kept when it was read, else a reading of the shelves now. */
    static List<Finding> findings(Graph g) {
        List<Finding> kept = READ.get(g);
        return kept != null ? kept : g.store().scanFindings().findings();
    }

    /** The graph as genealogy reads it: every enabled field's relations in every claim, and genealogy's own reading of who is a person. */
    public static Graph view(LibraryStore store) throws IOException { return Graph.build(store, new GenealogyProfile()); }

    /** Genealogy's view without the links worked out from the evidence: what {@link FamilyLinks} works them out from. */
    public static Graph unlinkedView(LibraryStore store) throws IOException { return Graph.build(store, GenealogyProfile.unlinked()); }

    /** One fact whichever way round a claim of genealogy's view wrote it ({@link Evidence#factKey} read genealogy's way). */
    static String factKey(String from, String predicate, String to) { return Evidence.factKey(new GenealogyProfile(), Set.of(), from, predicate, to); }

    /** The best that any claim gives each fact of genealogy's view ({@link Evidence#byFact} read genealogy's way). */
    static Map<String, Evidence> byFact(Graph g, Map<String, Finding> findings) { return Evidence.byFact(g, findings, new GenealogyProfile(), Map.of()); }

    /**
     * PEOPLE NOBODY MARKED. A family read and a tree file mark every person they file; a research run, a triple filled in afterwards or
     * another program's claim can name somebody new, such as a child an obituary names. In a claim about a family, whoever a family
     * relation names is a person, and so is the subject of a birth, a death, a burial, a baptism, a home, a job or a move. A claim is
     * about a family when it is genealogy's own work: the family's own texts and files wrote it, or a run somebody asked of genealogy filed
     * it ({@link Fields#ofClaim}). In genealogy's view a family relation that ties a claim to somebody who is a person already counts
     * too. "Instagram | parent | Meta" makes nobody a person. A name somebody described keeps what they wrote.
     */
    public static void read(Graph.Reading r, List<String> writers) {
        if (r.wide()) READ.put(r.graph(), r.findings());   // genealogy's own view: the claims it was read from, for the names and families worked out from it
        r.living(() -> {
            try {
                // the core graph's people are its names as written: it asks genealogy's view without links, which keys them the same way
                Graph v = r.wide() ? r.graph() : unlinkedView(r.store());
                return FamilyLiving.decide(FamilyLiving.claims(r.findings(), v), v.links() != null ? x -> x : v::nodeIdOf);
            } catch (IOException e) { return Map.of(); }
        });
        if (!r.wide() && !r.anyOwned()) return;   // the core graph of a library that holds no family work: nothing to read
        // the people the family's own texts and files are about, and whom the owner named a person. A family's own fact (its seat, its
        // branch, its founding) is about the family, and a family or a name nodes.md describes is never a person
        Set<String> known = new HashSet<>();
        for (Finding f : r.claims().values()) {
            if (!writers.contains(f.writer())) continue;
            // in genealogy's own view each side of a claim is the person it is linked to ({@link FamilyLinks})
            String rel = r.relationOf(f), subject = r.wide() ? r.graph().nodeOf(f, true) : r.nodeIdOf(f.triple().subject());
            String kind = r.curatedKind(subject);
            if (!FamilyHouses.OF_A_FAMILY.contains(rel) && !"family".equals(kind) && !"value".equals(kind)) known.add(subject);
            if (FamilyAccount.personToPerson(rel)) known.add(r.wide() ? r.graph().nodeOf(f, false) : r.nodeIdOf(f.triple().object()));
        }
        for (Map.Entry<String, String> k : r.curatedKinds().entrySet()) if ("person".equals(k.getValue())) known.add(k.getKey());
        Set<String> people = new LinkedHashSet<>();
        List<Graph.Edge> edges = r.edges();
        for (int round = 0; round < 8; round++) {
            boolean grew = false;
            for (Graph.Edge e : edges) {
                String pred = e.predicate();
                boolean both = FamilyAccount.personToPerson(pred) || FamilyAccount.associate(pred);
                if (!both && !ABOUT_A_PERSON.contains(pred) && !OF_A_LIFE.contains(pred)) continue;
                Finding f = r.claims().get(e.findingId());
                boolean family = r.owned(f);
                if (!family && !r.wide()) continue;   // the core graph reads genealogy's own work alone
                if (!family && pred.equals("heir-of")) continue;   // an ordinary text's heir is heir to an estate, a title or a post as often as to a person
                boolean aPerson = known.contains(e.from()) || people.contains(e.from());
                if (both) {
                    if (family || aPerson || known.contains(e.to()) || people.contains(e.to())) { grew |= people.add(e.from()); grew |= people.add(e.to()); }
                } else if (ABOUT_A_PERSON.contains(pred) ? family || aPerson : family) grew |= people.add(e.from());
            }
            if (!grew) break;
        }
        people.removeIf(id -> r.curatedKind(id) != null || r.nodeOf(id) == null || FamilyQuestions.unknown(r.nodeOf(id).label()));
        for (String id : people) r.markPerson(id);
    }
}
