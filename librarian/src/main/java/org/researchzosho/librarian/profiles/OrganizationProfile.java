package org.researchzosho.librarian.profiles;

import org.researchzosho.librarian.Profile;
import org.researchzosho.librarian.Researcher;
import org.researchzosho.librarian.Dossier;
import org.researchzosho.tools.ContentPolicy;
import java.io.IOException;
import org.researchzosho.librarian.CrossLinks;
import org.researchzosho.librarian.Analysis;
import org.researchzosho.librarian.Facets;
import org.researchzosho.librarian.Story;
import org.researchzosho.librarian.TimelineClaims;
import org.researchzosho.librarian.LibraryStore;
import org.researchzosho.librarian.Investigation;
import org.researchzosho.librarian.Vocabulary;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The organization field (0.6.6): a dossier on a company, a nonprofit, a university, a public body or a fictional institution, from the
 * registers and filings the organisation itself is in, published material and what the asker supplied. Its skeleton is the registry card
 * (names and numbers), structure, people, filings, money, products and marks, footprint, public record, reputation over time and what is
 * current; the people in it link to their own dossiers. Joins on its own when a question plainly asks about an organisation, or when asked
 * with --field organization.
 */
public final class OrganizationProfile implements Profile {
    @Override public String name() { return "organization"; }
    @Override public String description() { return "a dossier on a company, nonprofit, university or institution: its registers and filings, structure, people, money, marks, footprint and public record, each claim with the class of its source"; }
    @Override public List<Vocabulary.Term> predicates() {
        return List.of(
                new Vocabulary.Term("subsidiary-of", "subsidiary of", List.of("unit of", "division of", "子会社", "傘下"), ""),
                new Vocabulary.Term("parent-company-of", "parent company of", List.of("holding company of", "controls", "親会社"), ""),   // never "parent of": that is kinship, the genealogy module's word
                new Vocabulary.Term("incorporated-on", "incorporated on", List.of("founded on", "established on", "設立", "創業"), ""),
                new Vocabulary.Term("headquartered-in", "headquartered in", List.of("based in", "registered in", "本社", "本店"), ""),
                new Vocabulary.Term("has-officer", "has officer", List.of("chief executive", "director", "trustee", "代表取締役", "理事"), ""),
                new Vocabulary.Term("owns-stake-in", "owns stake in", List.of("shareholder of", "holds shares in", "出資", "株主"), ""),
                new Vocabulary.Term("filed", "filed", List.of("submitted", "reported to", "提出", "届出"), ""),
                new Vocabulary.Term("acquired", "acquired", List.of("bought", "took over", "買収", "取得"), ""),
                new Vocabulary.Term("merged-with", "merged with", List.of("combined with", "合併"), ""),
                new Vocabulary.Term("formerly-named", "formerly named", List.of("renamed from", "previously", "旧社名", "旧称"), ""),
                new Vocabulary.Term("registered-as", "registered as", List.of("LEI", "CIK", "EIN", "法人番号", "登記"), ""),
                new Vocabulary.Term("holds-mark", "holds mark", List.of("trademark", "registered mark", "商標"), ""),
                new Vocabulary.Term("received-funding-from", "received funding from", List.of("raised from", "grant from", "contract from", "補助金", "受注"), ""),
                new Vocabulary.Term("employs", "employs", List.of("headcount", "staff of", "従業員"), ""));
    }

    /** Words that ask for an organisation's dossier. Not "company" or "companies" alone: a patents question names companies without asking about one. */
    private static final List<String> WORDS = List.of(
            "company profile", "corporate profile", "about the company", "company's history", "corporate history", "history of the company", "history of the firm",
            "subsidiaries", "parent company", "who owns", "ownership of", "officers of", "board of directors", "board of trustees", "incorporated in", "the nonprofit",
            "the non-profit", "the foundation's", "the institute's", "university's history", "history of the university", "the university's", "dossier on",
            "due diligence on", "competitor analysis", "the startup", "the start-up", "org chart", "company structure",
            "会社の沿革", "沿革", "社史", "企業情報", "企業調査", "子会社", "親会社", "役員一覧", "株主構成", "法人情報", "大学の歴史", "財団の");
    /** A legal form (Inc., Ltd., 株式会社), a register number (LEI, CIK, 法人番号, a 13-digit number). */
    private static final Pattern SHAPES = Pattern.compile("\\b(Inc\\.|Corp\\.|Corporation|LLC|L\\.L\\.C\\.|Ltd\\.|Limited|GmbH|plc|S\\.A\\.|K\\.K\\.|Co\\., Ltd\\.)(?=[\\s,.?)]|$)"
            + "|株式会社|合同会社|有限会社|一般社団法人|一般財団法人|公益財団法人|学校法人|医療法人|\\bLEI\\b|\\bCIK\\b|法人番号|\\b\\d{13}\\b");
    @Override public boolean applies(String question) {
        String q = Vocabulary.norm(question);
        for (String w : WORDS) if (q.contains(w)) return true;
        return SHAPES.matcher(question).find();
    }
    @Override public boolean wantsRecords() { return true; }
    /** The report's Timeline, one claim a dated event, so the dossier and the story hold every event the run found and not only the ones a worker noted. */
    @Override public List<String> onReport(LibraryStore store, Investigation inv) throws IOException {
        String subject = TimelineClaims.subjectOf(store, inv);
        if (subject == null) return List.of();
        List<String> filed = TimelineClaims.file(store, inv, subject);
        CrossLinks.file(store, subject);   // a name another dossier's claims share becomes a question: how are they connected?
        Analysis.file(store, inv, subject);   // the forecasts to the ledger, the questions that would change the view to the frontier
        Story.refresh(store, subject);   // the story follows the shelves: written again when a subject that has one gains a report
        return filed;
    }
    /** What the shelves already hold on the subject, put before the run: earlier runs' claims, pages the person added, what was told. The run extends it and looks for what is not there. */
    @Override public String runEvidence(LibraryStore store, Researcher.Ask ask, ContentPolicy policy) throws IOException {
        String subject = TimelineClaims.subjectOf(ask.question());
        if (subject == null) return "";
        var d = Dossier.of(store, subject);
        if (!d.path("found").asBoolean()) return "";
        String md = d.path("markdown").asText();
        if (md.length() > 6000) md = md.substring(0, 6000).strip() + "\n…(the rest is on the shelves: shelf_search finds it, read_pages reads a claim or a report by its id)";
        return "WHAT THE LIBRARY ALREADY HOLDS ON " + d.path("subject").asText() + " — " + d.path("claims").asInt() + " claims, " + d.path("reports").asInt() + " reports. Build on it: cite a claim by its id when you use it, "
                + "look for what is not here, and where the record and these claims disagree say so.\n\n" + md;
    }
    @Override public List<String> chatTools() { return List.of("library_dossier", "library_tell"); }
    /** The Fields menu leads to the dossiers the shelves hold. */
    @Override public String chatNote() {
        return "An organisation's dossier, everything the library holds on it: library_dossier. When the person tells you what they know of an "
                + "organisation from the inside (how it was run, who was there, what happened and when), library_tell notes it in their words; say "
                + "\"noted:\" and what it raised, then ask the next open question on it, one at a time. What they tell may open a search: offer it.";
    }
    @Override public String register() {
        return "This is a dossier on an organisation. The registry card first: its legal names over time, its numbers (CIK, LEI, EIN, 法人番号), "
                + "where and when it was incorporated, its addresses. The registers are the organisation's own record and come before the web: US "
                + "filings at https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=0000858877&type=10-K and by words in them at "
                + "https://efts.sec.gov/LATEST/search-index?q=%22Cisco%20Systems%22&forms=10-K ; the legal-entity register at "
                + "https://search.gleif.org/#/search/simpleSearch=Cisco ; a nonprofit's Form 990 at https://projects.propublica.org/nonprofits/organizations/43291770 ; "
                + "a US college at https://collegescorecard.ed.gov/ ; Japan's 法人番号 at https://www.houjin-bangou.nta.go.jp/ , gBizINFO at https://info.gbiz.go.jp/ , "
                + "securities reports at https://disclosure2.edinet-fsa.go.jp/ , company histories (社史) at https://shashi.shibusawa.or.jp/ and in "
                + "https://ndlsearch.ndl.go.jp/ ; lobbying at https://lda.senate.gov/filings/public/filing/search/ ; federal contracts and grants at "
                + "https://www.usaspending.gov/search ; the organisation's own site over time at https://web.archive.org/web/*/cisco.com ; its "
                + "domains at https://crt.sh/?q=%25.cisco.com ; its marks at https://tmsearch.uspto.gov/ ; its patents in google-patents with assignee: before the name. "
                + "Then structure (parents, subsidiaries, ownership), the people (officers and directors over time, each a person with a handle), the "
                + "filings as a timeline, money (revenue, raises, grants, contracts, 990s), products and marks, footprint (offices, facilities, hiring, "
                + "domains), public record (courts, enforcement, sanctions, lobbying), reputation over time (press, dated), and what is current with "
                + "its date. Every claim carries the class of its source: public record, own publication (the organisation's own site, releases and "
                + "reports), third-party, supplied (what the asker gave), canon (a fictional institution's own work); and the date it held. Looking "
                + "for something and not finding it is a finding: name the register, the words and the date. A fictional institution has two "
                + "dossiers that never mix: in-world, from the works, each claim naming the work and edition; and real-world, who created it, who "
                + "holds the rights, adaptations, current status.";
    }
    @Override public String planRules() {
        return "This is a question about an organisation. The leaning of the question decides the Reading, never what is gathered: every facet is "
                + "researched in full whatever the question asks, so that the dossier is the whole organisation and the story can be written from it, "
                + "its founding and its people included. The first sub-question establishes the registry card: legal names over time, register "
                + "numbers, incorporation, addresses. The second is the work: what the organisation makes or does and is known for, its principal "
                + "products, projects or programmes, and each principal one, the flagship above all, gets a sub-question of its own: what it is and "
                + "claims to do, since when, who built it, how it works (the method, the data, the people), who pays for it, how it was received "
                + "and criticised, what it changed (an institute's index was a sentence in a dossier of registers, 2026-10-25). Then one "
                + "sub-question per facet: structure and ownership; people over time; filings and money; marks and patents; footprint; public "
                + "record; reputation and what is current. A fictional institution has in-world and "
                + "real-world as separate sub-questions. Name the collections in each sub-question, so the worker searches them before the web: "
                + "registry card: gleif, edgar-fulltext, opencorporates, propublica-nonprofits, college-scorecard, houjin-bangou, gbizinfo; people: "
                + "edgar-fulltext, propublica-nonprofits, diet-record; filings and money: edgar-fulltext, senate-lobbying, nsf-awards, "
                + "fec-contributions; products and marks: google-patents with assignee: before the name, epo-applicant, openfda-devices, "
                + "github-repos; footprint: crt-sh, gleif; public record: courtlistener, regulations-gov, federal-register, opensanctions; the history "
                + "and its people, the founders, the departures, which bets paid, in the books, the company histories, the memoirs and the founders' "
                + "oral histories: google-books, internet-archive, ndl-search, loc-newspapers, hacker-news, and the links hathitrust, chm-oral-histories "
                + "and shibusawa-shashi the worker opens by hand; a book that is open is read in full and cited by page. " + Facets.ORGANIZATION_PLAN;
    }
    @Override public String criticRules() {
        return "For an organisation's dossier the evidence is enough when the registry card rests on a register read directly, every facet has "
                + "claims or a search that found nothing in a named register, each claim carries its source class and date, and the current state "
                + "carries a date; and the work the organisation is known for has an account of its own, not a sentence: what it is, how it works, "
                + "how it was received. A gap worth another search names a register for the organisation's country not yet used, a parent or "
                + "subsidiary not yet followed, an officer not yet resolved, a principal product or project with no account, or a facet with no search." + Facets.ORGANIZATION_CRITIC;
    }
    @Override public String writerRules() {
        return "The Answer section, first, is the Reading: what the record shows for the leaning of the question, every sentence cited. Then: Registry card; "
                + "The work (each principal product, project or programme with its own account: what, since when, who, how it works, how received, what it changed); "
                + "Customers, peers and alternatives (who pays for or uses the work, by name where the record says; for each work item, who else does this and what the customer would use instead, as the organisation names them and as the press does, each with its source; none named is said so); "
                + "Structure; Power and governance (who holds power over it: owners and their stakes, the board and its chair, the chief executive, funders, regulators or customers with leverage; how it decides); "
                + "People (each person named with a handle, so their own dossier can be linked); Decisions (" + Facets.DECISION_LINE + "); " + Facets.STATEMENTS_WRITE + "Filings (as a timeline); Money; "
                + "Workforce and signals (headcount over time, hiring and departures, the cadence of filings, releases and announcements, and what changed it); "
                + "Products and marks; Footprint; " + Facets.MEDIA_WRITE + "Public record; Reputation over time; Current (with its date); Looked for and not found. Every claim "
                + "carries its date. The library marks each reference in the list with its source class (public record, own publication, "
                + "third-party, supplied), so cite the reference and the class follows. Current means as of today, the date at the top of this "
                + "prompt, never a date from memory: write that date and name the latest dated source behind it. For a fictional institution "
                + "keep In-world and Real-world separate; In-world is canon, by work and edition. " + Analysis.WRITER_RULES;
    }
    @Override public String extractionRule() {
        return "A claim about an organisation names it by its legal name or a register number, carries the class of its source (public record, "
                + "own publication, third-party, supplied, canon) and the date it held (valid_as_of): for an event, its date; for a state of affairs, the "
                + "day it was last true. Its triple has the organisation as the subject, one of the field's relations as the predicate (subsidiary of, "
                + "has officer, acquired, incorporated on, headquartered in, holds mark) and the other organisation, person, place or mark as the object; "
                + "amounts and years go in the claim, never in the object.";
    }
}
