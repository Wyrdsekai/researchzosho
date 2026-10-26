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
 * The person field (0.6.6): a dossier on one person, living, dead or fictional, public or private, from public records, published material
 * and what the asker supplied. What it adds is the shape of the answer, a fixed skeleton with a reading for the question's leaning, the
 * class of source on every claim, and identity resolved by handles the way the genealogy field resolves it by the family. Joins on its own
 * when a question plainly asks about a person, or when asked with --field person. The library does not decide who may be researched: the
 * model the person chose does.
 */
public final class PersonProfile implements Profile {
    @Override public String name() { return "person"; }
    @Override public String description() { return "a dossier on a person: identity, timeline, roles, holdings, material, associations, public record, what is current, each claim with the class of its source"; }
    @Override public List<Vocabulary.Term> predicates() {
        return List.of(
                new Vocabulary.Term("worked-at", "worked at", List.of("works at", "employee of", "勤務", "在籍"), ""),
                new Vocabulary.Term("founded", "founded", List.of("founder of", "co-founded", "co-founder of", "創業", "設立"), ""),
                new Vocabulary.Term("holds-role", "holds role", List.of("serves as", "chair of", "director of", "officer of", "役員", "就任"), ""),
                new Vocabulary.Term("studied-at", "studied at", List.of("graduated from", "degree from", "卒業", "在学"), ""),
                new Vocabulary.Term("affiliated-with", "affiliated with", List.of("fellow of", "belongs to", "会員", "所属"), ""),   // not "member of": the genealogy module's word for a household
                new Vocabulary.Term("associated-with", "associated with", List.of("co-author of", "partner of", "colleague of", "共著", "共同"), ""),
                new Vocabulary.Term("owns", "owns", List.of("holds", "shareholder of", "保有", "所有"), ""),
                new Vocabulary.Term("based-in", "based in", List.of("lives in", "resident of", "在住", "居住"), ""),
                new Vocabulary.Term("appeared-at", "appeared at", List.of("spoke at", "keynote at", "interviewed on", "登壇", "出演"), ""),
                new Vocabulary.Term("wrote", "wrote", List.of("author of", "published", "著", "執筆"), ""),
                new Vocabulary.Term("party-to", "party to", List.of("plaintiff in", "defendant in", "named in", "当事者"), ""),
                new Vocabulary.Term("posts-as", "posts as", List.of("handle", "account", "アカウント"), ""));
    }

    /** Words that ask for a person's dossier. Not a name alone, and not "who were the parents": that is the genealogy field's. */
    private static final List<String> WORDS = List.of(
            "dossier", "target package", "background check", "background on", "profile of", "biography of", "biographical", "career of", "due diligence on",
            "track record of", "what is known about", "everything about", "everything known about", "who exactly is", "life and career", "person of interest",
            "人物像", "経歴", "プロフィール", "身元", "素性", "来歴", "人物について", "人物調査");
    /** "Who is Firstname Lastname", a LinkedIn profile address. */
    private static final Pattern SHAPES = Pattern.compile("\\b[Ww]ho is [A-Z][\\w.'-]+ [A-Z]|linkedin\\.com/in/|とは誰");
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
        return "A person's dossier, everything the library holds on them: library_dossier. When the person tells you something about a subject from their "
                + "own knowledge (what it was like, who was there, an incident, a date), library_tell notes it in their words; say \"noted:\" and what it "
                + "raised, then ask them the next open question on that subject, one at a time. What they tell may open a search: offer it.";
    }
    @Override public String register() {
        return "This is a dossier on a person. Identity first: resolve who they are by handles, the facts that tell them from a namesake (an employer "
                + "and the years, a title, a place, a co-author, a book, a site, an account), and set namesakes aside by name. Every fact landed is a new "
                + "handle for the next search. Start with what the shelves hold, including any page the person who asked added to the library (that is "
                + "class supplied); then the index of who is written about, https://www.wikidata.org/wiki/Q312740 is such a record; then each facet in "
                + "turn with the collections made for it: roles and filings in the companies' registers (https://efts.sec.gov/LATEST/search-index?q=%22Lamina1%22&forms=D "
                + "finds a name in US filings; https://www.fec.gov/data/receipts/?contributor_name=Stephenson%2C+Neal gives employer and occupation on a "
                + "contribution), publications in https://api.openalex.org/authors?search=Neal%20Stephenson and at https://orcid.org/0000-0002-1825-0097 , "
                + "words said in Japan's Diet at https://kokkai.ndl.go.jp/ , posts at https://bsky.app/profile/nealstephenson.bsky.social , a page that "
                + "has vanished at https://web.archive.org/web/2024/https://example.com/about . Every claim carries the class of its source: public "
                + "record (a register, a court, a filing, a catalogue), own publication (the person's own words, site or account), third-party (press "
                + "and reporting), supplied (what the asker gave), canon (a fictional subject's own work, by edition), and the date it held. Looking for "
                + "something and not finding it is a finding: say which collection was searched, with what words, as of what date. A fictional person "
                + "has two dossiers that never mix: in-world, from the works themselves, each claim naming the work and the edition; and real-world, "
                + "the creation, the author, the rights and who holds them now, adaptations, reception, current status. James Bond of the novels and "
                + "of the films are two canons: say which. Dated public traces (appearances, posts, filings, releases) are the pattern of a life as "
                + "the public record shows it; a trend read from them says what it rests on.";
    }
    @Override public String planRules() {
        return "This is a question about a person. The leaning of the question decides the Reading, never what is gathered: every facet is "
                + "researched in full whatever the question asks, so that the dossier is the whole person and the story can be written from it. The "
                + "first sub-question resolves the identity: who they are, by which handles, and which namesakes are set aside. The second is what they "
                + "do, which for a person says who they are: the role now and what it produces; the work they are known for and how it was received; how "
                + "the doing changed over the career; and what they say they are about in their own words (talks, interviews, bios), kept apart from how "
                + "others describe them. Then one sub-question "
                + "per facet: the life (born where, grew up where, studied where, lived where, and what of the life the work drew on); timeline and "
                + "roles; holdings and filings; material they made, complete, every book, paper, work of code, talk and series with its year, from the "
                + "catalogues (open-library-author, google-books, openalex-works, wikidata), not only the works that bear on the question, and the "
                + "principal work, the one the person is known for, gets a sub-question of its own: what it is, how it was made and received, what "
                + "it changed; associations "
                + "(co-authors, co-founders, employers, organisations); public record (courts, registers, sanctions, lobbying); what is current. For a "
                + "fictional person, in-world and real-world are separate sub-questions. Name the "
                + "collections in each sub-question, so the worker searches them before the web: identity: wikidata, openalex-authors, orcid, "
                + "bluesky-accounts, github-repos; roles, holdings and filings: edgar-fulltext, fec-contributions, gleif, propublica-nonprofits, "
                + "senate-lobbying; material: openalex-works, internet-archive, hacker-news, github-repos, qiita; public record: courtlistener, "
                + "opensanctions, google-patents, epo-inventor; the life in the books, biographies, memoirs, oral histories and long-form journalism: "
                + "google-books, internet-archive, hacker-news, and the links hathitrust and chm-oral-histories the worker opens by hand; a Japanese "
                + "subject: diet-speaker, diet-record, ndl-search, cinii, wikipedia-ja. " + Facets.PERSON_PLAN;
    }
    @Override public String criticRules() {
        return "For a dossier the evidence is enough when the identity is resolved by named handles, every facet has either claims or a search "
                + "that found nothing in a named collection, each claim carries its source class and date, and the current state carries a date; the "
                + "question's leaning does not excuse a facet: a person's works are complete when the claims list as many as the catalogue counts, and "
                + "the life has its places and its schooling. A "
                + "gap worth another search names a facet not yet searched, a handle not yet followed (an employer, a co-author, an account), or a "
                + "collection made for the facet that was not used." + Facets.PERSON_CRITIC;
    }
    @Override public String writerRules() {
        return "The Answer section, first, is the Reading: what the record shows for the leaning of the question, every sentence cited, nothing in it "
                + "that the sections below do not carry; for a question of the shape 'who is X', the Reading opens with what they do. Then the sections in this "
                + "order: Identity (names, aliases, how resolved, namesakes set aside); What they do (now: the role and what it produces; known for: the "
                + "principal work and how it was received; the arc: how the doing changed over the career; in their own words: what they say they are about, "
                + "apart from how others describe them); "
                + "Timeline (year by year); Roles; Decisions (the principal moves of the career, " + Facets.DECISION_LINE + "); " + Facets.STATEMENTS_WRITE + "Holdings and filings; Material; "
                + "Associations; Influences (who and what shaped them, as they said it; whom they shaped and who credits them); " + Facets.MEDIA_WRITE + "Public record; Current (with the date it is "
                + "current as of); Looked for and not found. Every claim carries its date. The library marks each reference in the list with "
                + "its source class (public record, own publication, third-party, supplied), so cite the reference and the class follows. "
                + "Current means as of today, the date at the top of this prompt, never a date from memory: write that date and name the "
                + "latest dated source behind it. For a fictional person keep In-world and Real-world as separate sections and never let one "
                + "borrow from the other; In-world is canon, by work and edition. " + Analysis.WRITER_RULES
                + " For a person, Conjectures and Forecasts are written when the question asks what to expect of them or what they will do next; "
                + "otherwise Judgments alone, and under Conjectures the one line: Not asked: the question did not ask what to expect.";
    }
    @Override public String extractionRule() {
        return "A claim about a person names the person with one handle that tells them from a namesake, carries the class of its source (public "
                + "record, own publication, third-party, supplied, canon) and the date it held (valid_as_of): for a role, the years it ran, not the day "
                + "of the run. Its triple has the person as the subject, one of the field's relations as the predicate (worked at, founded, holds role, "
                + "studied at, wrote, based in) and the organisation, work or place as the object; the years go in the claim, never in the object. "
                + "Each work is its own claim with its year (person wrote work, the year in the claim), so the story can place it in its decade.";
    }
}
