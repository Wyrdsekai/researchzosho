package org.researchzosho.librarian.profiles;

import org.researchzosho.librarian.Gedcom;
import org.researchzosho.librarian.LibraryStore;
import org.researchzosho.librarian.Profile;
import org.researchzosho.librarian.Vocabulary;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * The genealogy profile: kinship predicates, GEDCOM in and out, and the living-person rule. Written
 * against the core's graph the way any field's profile is — persons, places and events are core
 * node kinds; what this profile adds is the vocabulary of relations a family tree asserts, an
 * importer that turns a GEDCOM file into nodes and draft findings with the file as their source,
 * an exporter that hands the person subgraph back as GEDCOM, and the rule that a living person's
 * node is private: the desk never shows it to another patron and the exporter skips it by default.
 *
 * <p>Off by default. {@code researchzosho profile enable genealogy}.
 */
public final class GenealogyProfile implements Profile {

    @Override public String name() { return "genealogy"; }

    @Override public String description() { return "kinship relations, GEDCOM import and export, living persons private"; }

    @Override public List<Vocabulary.Term> predicates() {
        return List.of(
                new Vocabulary.Term("parent-of", "is a parent of", List.of("father of", "mother of", "parent"), ""),
                new Vocabulary.Term("child-of", "is a child of", List.of("son of", "daughter of", "child"), ""),
                new Vocabulary.Term("married-to", "was married to", List.of("spouse of", "husband of", "wife of", "married", "spouse"), ""),
                new Vocabulary.Term("born-in", "was born in", List.of("birthplace", "born at", "born"), ""),
                new Vocabulary.Term("died-in", "died in", List.of("place of death", "died at", "died"), ""),
                new Vocabulary.Term("lived-in", "resided in", List.of("resided in", "residence", "lived at"), ""),
                new Vocabulary.Term("migrated-to", "emigrated or immigrated to", List.of("emigrated to", "immigrated to", "moved to", "arrived in"), ""),
                new Vocabulary.Term("buried-in", "was buried in", List.of("burial", "interred in"), ""),
                new Vocabulary.Term("occupation", "worked as", List.of("worked as", "profession", "was a"), ""));
    }

    @Override public List<String> nodeKinds() { return List.of("family"); }

    @Override public String register() {
        return "Family history: a name is not an identity — the same name and dates in the same county are two people until "
                + "a spouse, a child, an occupation or an address agrees. A public tree or an index is a hint, not a source; "
                + "cite the original record. Fifty copies of one misread headstone are one source. A search of a named "
                + "archive that found nothing is evidence: record it. Living persons are private.";
    }

    @Override public String usage() {
        return "genealogy import <file.ged> [--include-living]   nodes + draft findings from a GEDCOM file\n"
             + "              genealogy export <person> [--include-living]     the person subgraph as GEDCOM";
    }

    @Override public Integer cli(LibraryStore store, String[] args) throws Exception {
        if (args.length < 3) { System.err.println("usage: researchzosho genealogy import <file.ged> | export <person>"); return 2; }
        boolean living = Arrays.asList(args).contains("--include-living");
        switch (args[2]) {
            case "import" -> {
                if (args.length < 4) { System.err.println("usage: researchzosho genealogy import <file.ged> [--include-living]"); return 2; }
                Gedcom.Outcome o = Gedcom.importFile(store, Path.of(args[3]), living);
                System.out.println("imported " + o.persons() + " person(s), " + o.families() + " famil(ies), " + o.findings() + " draft finding(s); "
                        + o.privateNodes() + " living person(s) marked private" + (o.problems().isEmpty() ? "" : "\n  " + String.join("\n  ", o.problems())));
                return 0;
            }
            case "export" -> {
                if (args.length < 4) { System.err.println("usage: researchzosho genealogy export <person> [--include-living]"); return 2; }
                System.out.print(Gedcom.export(store, args[3], living));
                return 0;
            }
            default -> { System.err.println("usage: researchzosho genealogy import <file.ged> | export <person>"); return 2; }
        }
    }
}
