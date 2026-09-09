package org.researchzosho.librarian.profiles;

import org.researchzosho.librarian.Profile;
import org.researchzosho.librarian.Vocabulary;

import java.util.List;

/**
 * The science profile: citation records (DOI, arXiv, PubMed), preprint versions, dataset records,
 * BibTeX. Its machinery lives in the core classes {@code Citations}, {@code Preprints} and
 * {@code Bibliography} because every field cites; what the profile adds is the vocabulary of relations
 * scientific findings assert, and the register. On by default.
 */
public final class ScienceProfile implements Profile {

    @Override public String name() { return "science"; }

    @Override public String description() { return "citations from the record, preprint versions, datasets as sources, BibTeX"; }

    @Override public List<Vocabulary.Term> predicates() {
        return List.of(
                new Vocabulary.Term("authored", "wrote or co-wrote a work", List.of("wrote", "co-authored", "is author of", "author of"), ""),
                new Vocabulary.Term("cites", "refers to another work as a source", List.of("references", "cited", "builds on"), ""),
                new Vocabulary.Term("measured", "reports a measurement of", List.of("reports", "found", "observed"), ""),
                new Vocabulary.Term("replicates", "reproduces a result of", List.of("reproduces", "confirms"), ""),
                new Vocabulary.Term("contradicts", "reports a result incompatible with", List.of("refutes", "disagrees with"), ""),
                new Vocabulary.Term("uses-dataset", "draws its data from", List.of("dataset", "data from"), ""),
                new Vocabulary.Term("patented", "holds a patent on", List.of("patent on", "inventor of", "invented"), ""));
    }

    @Override public String register() {
        return "For a paper, a preprint, a dataset or software, record the VERSION (arXiv vN, the dataset release, the "
                + "software version, the DOI) the way an edition is recorded for a text.";
    }
}
