package org.researchzosho.librarian.profiles;

import org.researchzosho.librarian.Profile;
import org.researchzosho.librarian.Vocabulary;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The science profile: citation records (DOI, arXiv, PubMed), preprint versions, dataset records,
 * BibTeX. Its machinery lives in the core classes {@code Citations}, {@code Preprints} and
 * {@code Bibliography} because every field cites; what the profile adds is the vocabulary of relations
 * scientific findings assert, and the register. It joins a run whose question rests on the scientific record, by its words.
 */
public final class ScienceProfile implements Profile {

    @Override public String name() { return "science"; }

    @Override public String conventionName() { return "science"; }

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

    /**
     * Words that say a question rests on the scientific record, each unambiguous on its own: a bare "paper" is also a bag, "scientific" a
     * calculator, "replication" a database's, and "estudio" a flat in a Spanish home search.
     */
    private static final List<String> WORDS = List.of(
            "scientific evidence", "scientific consensus", "scientific study", "scientific studies", "scientific paper", "scientific literature",
            "scientific research", "peer-reviewed", "peer reviewed", "preprint", "arxiv", "pubmed", "medline", "meta-analysis", "meta analysis",
            "systematic review", "clinical trial", "randomized controlled", "randomised controlled", "placebo", "cohort study", "case-control study",
            "journal article", "research paper", "academic paper", "the literature", "studies show", "a study found", "studies found",
            "published research", "論文", "臨床試験", "メタ分析", "メタアナリシス", "査読", "学術", "先行研究");
    /** A DOI, an arXiv identifier, a PubMed id. */
    private static final Pattern SHAPES = Pattern.compile("\\b10\\.\\d{4,9}/\\S+|\\barxiv:\\s?\\d{4}\\.\\d{4,5}|\\bpmid:?\\s?\\d{5,9}", Pattern.CASE_INSENSITIVE);

    @Override public boolean applies(String question) {
        String q = Vocabulary.norm(question == null ? "" : question);
        for (String w : WORDS) if (q.contains(w)) return true;
        return SHAPES.matcher(question == null ? "" : question).find();
    }

    @Override public String register() {
        return "For a paper, a preprint, a dataset or software, record the VERSION (arXiv vN, the dataset release, the "
                + "software version, the DOI) the way an edition is recorded for a text.";
    }
}
