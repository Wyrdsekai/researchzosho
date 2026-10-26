package org.researchzosho.librarian.profiles;

import org.researchzosho.librarian.Profile;
import org.researchzosho.librarian.Vocabulary;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The law field (0.6.6): statutes, regulations, court decisions and ordinances. What it adds is the primary texts a web engine buries
 * under commentary (the courts' own decisions, the codes and registers of regulations, Japan's e-Gov laws) and the way a legal claim is
 * judged: by the text itself, read, with the jurisdiction and the date it held. A run of this field is offered the law collections; any
 * other run is untouched. Joins on its own when a question is plainly legal, or when asked with --field law.
 */
public final class LawProfile implements Profile {
    @Override public String name() { return "law"; }
    @Override public String description() { return "statutes, regulations, court decisions and ordinances: the primary texts first, each claim with its jurisdiction and the date the law stood"; }
    @Override public List<Vocabulary.Term> predicates() {
        return List.of(
                new Vocabulary.Term("decided-by", "decided by", List.of("ruled by", "held by", "判決した"), ""),
                new Vocabulary.Term("decided-on", "decided on", List.of("judgment date", "ruling of", "判決日"), ""),
                new Vocabulary.Term("cites", "cites", List.of("relies on", "follows", "citing", "引用"), ""),
                new Vocabulary.Term("overrules", "overrules", List.of("overruled", "abrogated", "判例変更"), ""),
                new Vocabulary.Term("interprets", "interprets", List.of("construes", "applies the statute", "解釈"), ""),
                new Vocabulary.Term("enacted-on", "enacted on", List.of("promulgated on", "signed into law on", "公布"), ""),
                new Vocabulary.Term("in-force-from", "in force from", List.of("effective from", "took effect on", "施行"), ""),
                new Vocabulary.Term("amends", "amends", List.of("amended by", "改正"), ""),
                new Vocabulary.Term("repeals", "repeals", List.of("repealed by", "廃止"), ""),
                new Vocabulary.Term("codified-at", "codified at", List.of("codified in", "§", "条に規定"), ""),
                new Vocabulary.Term("applies-in", "applies in", List.of("governs in", "jurisdiction of", "管轄"), ""),
                new Vocabulary.Term("issued-by", "issued by", List.of("promulgated by", "adopted by", "省令", "条例"), ""));
    }

    /** Words that mean law. Not "law" alone (Benford's law, Moore's law) and not "regulation" alone (a protein's). */
    private static final List<String> WORDS = List.of(
            "statute", "statutory", "case law", "caselaw", "court ruling", "court held", "the court", "supreme court", "court of appeals", "appeals court",
            "circuit court", "district court", "ordinance", "municipal code", "u.s.c.", "u.s. code", "cfr", "code of federal regulations", "federal register",
            "plaintiff", "defendant", "appellant", "statute of limitations", "precedent", "jurisdiction", "lawsuit", "litigation", "legislation", "legal",
            "tort", "constitutional", "unconstitutional", "attorney", "judgment", "judgement", "legally", "zoning", "bylaw", "by-law",
            "判例", "裁判例", "条例", "法律", "法令", "施行", "改正", "最高裁", "高裁", "地裁", "訴訟", "判決", "違憲", "条文", "法第", "弁護士", "民法", "刑法", "商法");
    /** A case name (Roe v. Wade), a reporter citation (410 U.S. 113), a section sign, a Japanese case number (令和5年(受)第123号). */
    private static final Pattern SHAPES = Pattern.compile("\\b[A-Z][\\w.'-]* v\\. [A-Z]|\\b\\d{1,4} [A-Z][A-Za-z.]*\\.? ?\\d?[a-z]{0,2} \\d{1,5}\\b|§|[令平昭][和成]\\d+年\\(|令和\\d+年|平成\\d+年");
    @Override public boolean applies(String question) {
        String q = Vocabulary.norm(question);
        for (String w : WORDS) if (q.contains(w)) return true;
        return SHAPES.matcher(question).find();
    }
    @Override public boolean wantsRecords() { return true; }
    @Override public String register() {
        return "Find the primary text and read it: the opinion, the statute's section, the regulation, the ordinance. Commentary (a law firm's note, a "
                + "blog, an encyclopaedia, a news story) is a path to the primary text, never the source for a claim about what the law says. The law "
                + "collections search the courts' and the governments' own records: use them before a web engine. A citation is fetched, not searched: a "
                + "US case 574 F.3d 1228 is at https://www.courtlistener.com/c/F.3d/574/1228/ ; a US Code section 17 U.S.C. § 107 is at "
                + "https://www.law.cornell.edu/uscode/text/17/107 ; a CFR section 37 CFR 1.53 is at https://www.ecfr.gov/current/title-37/section-1.53 ; a "
                + "Federal Register document 2026-12345 is at https://www.federalregister.gov/d/2026-12345 ; a Japanese law is found by its name in "
                + "egov-laws and read at the e-Gov address the hit gives; a Japanese judgment is found at the courts' 裁判例検索 "
                + "(https://www.courts.go.jp/app/hanrei_jp/search1) by its case number such as 令和5年(受)第123号, or through a web search, and read as "
                + "the PDF it links. A state's code is on its legislature's site and mirrored at https://www.law.cornell.edu/states ; a city's code is "
                + "on its host (Municode, American Legal, eCode360): search the web for the city's name with \"code of ordinances\" and read the "
                + "section. Quote the operative words. For every claim say which jurisdiction it holds in and as of what date: law changes, a statute's "
                + "text is dated by its last amendment, and a decision by the day it was handed down.";
    }
    @Override public String planRules() {
        return "This is a question about law. Split it by jurisdiction first (which country, state or prefecture, city) and then by kind of "
                + "authority: the statute or ordinance, the regulations under it, the court decisions reading it. When the question names a "
                + "citation, one sub-question fetches that text itself.";
    }
    @Override public String criticRules() {
        return "For a question about law the evidence is enough when each claim rests on a primary text (an opinion, a statute, a regulation, an "
                + "ordinance) read directly and dated, with its jurisdiction named; a claim that rests on commentary alone is a gap. A gap worth "
                + "another search names a jurisdiction or a level (federal, state, municipal; 国, 都道府県, 市町村) not yet covered, or a cited "
                + "authority not yet read.";
    }
    @Override public String writerRules() {
        return "Say the jurisdiction and the date the law stood in the first sentence. Cite primary texts by their own citation (the reporter and "
                + "page, the section number, the 法令番号, the 事件番号) with the link. Keep what the text says apart from how courts have read it. "
                + "Where levels differ, give each its own paragraph: federal, state, municipal; 国, 都道府県, 市町村. Say plainly what was not found "
                + "at a level. This is research, not advice: say what the texts say and who decided what, and leave the person's case to them.";
    }
    @Override public String extractionRule() {
        return "A claim about law carries the jurisdiction it holds in and the date it held (valid_as_of): the decision date for a case, the "
                + "enforcement or last-amendment date for a statute, regulation or ordinance.";
    }
}
