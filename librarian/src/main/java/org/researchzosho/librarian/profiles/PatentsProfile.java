package org.researchzosho.librarian.profiles;

import org.researchzosho.librarian.Profile;
import org.researchzosho.librarian.Vocabulary;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The patents field (0.6.6): prior-art search across the offices, and a patent read by its number: claims, dates, assignee, family,
 * status. What it adds is the offices' own collections (Google Patents without a key, the EPO's Open Patent Services and Google Patents
 * through SerpApi with one), and the way a patent claim is judged: from the publication itself. A run of this field is offered the patent
 * collections; any other run is untouched. Joins on its own when a question is plainly about patents, or when asked with --field patents.
 */
public final class PatentsProfile implements Profile {
    @Override public String name() { return "patents"; }
    @Override public String description() { return "patents and applications: prior-art search across the offices, and a patent read by its number: claims, dates, assignee, family, status"; }
    @Override public List<Vocabulary.Term> predicates() {
        return List.of(
                new Vocabulary.Term("has-inventor", "has the inventor", List.of("inventor", "発明者"), ""),
                new Vocabulary.Term("assigned-to", "assigned to", List.of("assignee", "applicant", "owned by", "出願人", "権利者"), ""),
                new Vocabulary.Term("filed-on", "filed on", List.of("filing date", "出願日"), ""),
                new Vocabulary.Term("published-on", "published on", List.of("publication date", "公開日"), ""),
                new Vocabulary.Term("granted-on", "granted on", List.of("grant date", "issued on", "登録日"), ""),
                new Vocabulary.Term("claims-priority-to", "claims priority to", List.of("priority of", "優先権"), ""),
                new Vocabulary.Term("same-family-as", "in the same family as", List.of("counterpart of", "パテントファミリー"), ""),
                new Vocabulary.Term("cites-prior-art", "cites as prior art", List.of("cited reference", "引用文献"), ""),
                new Vocabulary.Term("classified-as", "classified as", List.of("cpc class", "ipc class", "分類"), ""),
                new Vocabulary.Term("expires-on", "expires on", List.of("expiry", "存続期間満了"), ""),
                new Vocabulary.Term("has-status", "has the status", List.of("legal status", "権利状態"), ""));
    }

    /** Words that mean a patent question. Not the bare verb ("what did she patent"): that is a person's doing, and the family-history field's when she is known. */
    private static final List<String> WORDS = List.of(
            "patents", "patented", "patentable", "a patent", "the patent", "patent for", "patent on", "patent application", "patent claim", "patent number", "prior art", "prior-art", "uspto", "espacenet", "patentscope", "j-platpat", "patent office",
            "provisional application", "pct application", "freedom to operate", "claim 1", "independent claim", "cpc class",
            "特許", "出願", "先行技術", "実用新案", "意匠登録", "特許庁", "請求項", "発明");
    /** A publication number as an office writes it: US12450652B1, EP1000000, WO2024123456, JP2020123456A. */
    private static final Pattern NUMBER = Pattern.compile("\\b(US|EP|WO|JP|CN|KR|DE|GB|FR|CA|AU)\\s?\\d{6,12}\\s?[A-C]?\\d?\\b");
    @Override public boolean applies(String question) {
        String q = Vocabulary.norm(question);
        for (String w : WORDS) if (q.contains(w)) return true;
        return NUMBER.matcher(question).find();
    }
    @Override public boolean wantsRecords() { return true; }
    @Override public String register() {
        return "Search the offices' own records through the patent collections first (google-patents by words, epo-query by applicant, inventor "
                + "and title together, epo-applicant, epo-inventor), in English and in the office's language, then read the patent itself: every "
                + "patent is at https://patents.google.com/patent/US12450652B1/en with the number as the office writes it, kind code included; read "
                + "the claims, the priority line, the citations and the family. A provisional application is never published: it shows as the "
                + "priority claim of a later application, and its text is in that application's file wrapper at the USPTO. An application filed "
                + "with a non-publication request is unseen until grant: its assignment record is public even so (https://assignment.uspto.gov/patent/ "
                + "by assignee), and any foreign family publishes at eighteen months in Espacenet or PATENTSCOPE. Japan publishes every "
                + "application at eighteen months; J-PlatPat (https://www.j-platpat.inpit.go.jp/) reads one by number. For each patent note its "
                + "number, office, filing and publication or grant dates, assignee, and status when read.";
    }
    @Override public String planRules() {
        return "This is a question about patents. Split it into the words of the invention (several phrasings, in English and the office's "
                + "language), the applicants and inventors likely to hold it, and the classification; when the question names a patent by number, "
                + "one sub-question reads that patent itself.";
    }
    @Override public String criticRules() {
        return "For a patent question the evidence is enough when the candidates were found through the patent collections (not a web engine "
                + "alone), each read from its own publication with number, dates, assignee and claims, and more than one office was searched. A gap "
                + "worth another search names an office, an applicant, a classification or a language not yet searched.";
    }
    @Override public String writerRules() {
        return "Give the patents in a table: number, title, assignee, filing date, publication or grant date, office, what it claims, why it "
                + "fits. For prior art order by priority date, oldest first. Say what was searched where, and which offices were not. Say plainly "
                + "that unpublished applications cannot be seen, and what their tracks showed if any.";
    }
    @Override public String extractionRule() {
        return "A claim about a patent carries its publication number as the office writes it and the date of the publication it was read from.";
    }
}
