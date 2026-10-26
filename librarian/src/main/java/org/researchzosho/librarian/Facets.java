package org.researchzosho.librarian;

/**
 * The facets a target package carries beyond the registers (0.6.6, agreed 2026-10-25): the work with its customers and competitors; who holds
 * power over the organisation and how it decides; the people in power in depth, each decision dated with the reason they gave in their own
 * words; the workforce and the cadence of the organisation's signals; and for a person, their decisions with their reasons and their
 * influences. What the planner, the critic and the writer are told; the Decisions section is a dated list the Timeline step reads into claims.
 */
public final class Facets {
    private Facets() { }

    public static final String STATEMENTS_PLAN = " Statements: what they said in their own words, dated and placed — interviews, talks, posts, press releases, letters to "
            + "shareholders, testimony — from youtube and podcasts (the transcript, with the minute), bluesky-accounts then bluesky-posts by the handle found (the feed holds reposts too: a quote is theirs only when the author's handle is theirs), hacker-news, "
            + "qiita, diet-record for a Japanese politician, the press; each a quote with its date, its venue and its source, the quote as the source has it. Media: photographs, "
            + "portraits, logos, scans and video of the subject with their provenance — who made each, when, where it is published, under which licence — from "
            + "wikimedia-commons (the file page carries the uploader, the date and the licence; read it), youtube, internet-archive, europeana and the subject's own press "
            + "kit; each item a record of the file, never the file itself.";
    public static final String STATEMENTS_WRITE = "Statements (what they said in their own words, one a line: YYYY-MM-DD or YYYY-MM: VENUE — \"the quote\" [n]); ";
    public static final String MEDIA_WRITE = "Media (one a line: YEAR: what it shows — by whom — where it is published, under which licence — the address [n]); ";
    public static final String STATEMENTS_CRITIC = " A subject with a public voice and no statement in their own words on record is a gap, and so is one whose media were not looked for in a named collection.";

    /** The shape of a Decisions section: one decision a line, dated, the reason in the decider's own words, the outcome. */
    public static final String DECISION_LINE = "a dated list, one decision a line: YYYY-MM-DD or YYYY-MM: WHO decided WHAT — the reason they gave, quoted in their own "
            + "words where the record has them [n] — who else was involved — the outcome [n]; a decision is one the subject's own people took (the chair, the chief "
            + "executive, the owners, the person), and what others did to the subject — a court's ruling, a donor's halt, a demand from outside — belongs in the Timeline";

    public static final String ORGANIZATION_PLAN = "Four more sub-questions, in this order of weight. Customers, peers and alternatives: who pays for or uses the "
            + "work, by name where the record says (customers, funders, members, the share of revenue each gives where filed); and for each principal work "
            + "item, who else does this and what the customer would use instead — the peers and alternatives the organisation itself names (a 10-K's "
            + "Competition section, found by searching edgar-fulltext for the company's name with the word competition; its own pitch) and up to three the "
            + "trade press or analysts name, each with its source. A nonprofit's peers are the others doing the same work; a small organisation's "
            + "alternative is often an incumbent, a bigger vendor's side feature or doing without; none named in the record is a finding, written as such. "
            + "propublica-nonprofits for funders. Power and governance: who holds power over the organisation — the owners and their stakes, the board and its chair, "
            + "the chief executive, the funders, regulators or customers with leverage — and how it decides (charter, bylaws, voting rights, "
            + "activist stakes); edgar-fulltext for proxy statements, gleif, opencorporates, propublica-nonprofits. The people in power in depth: for "
            + "the chair, the chief executive, the owners and the founders, the decisions each made or announced, dated, with the reason they gave in "
            + "their own words, who else was involved and what came of it, from letters to shareholders, interviews, oral histories, press "
            + "releases, earnings calls and memoirs (google-books, chm-oral-histories, youtube, podcasts); each such person named with a handle so "
            + "their own dossier links. Workforce and signals: headcount over time, hiring and departures, the cadence of filings, releases and "
            + "announcements, offices opened and closed, and what changed that cadence; edgar-fulltext, github-repos, crt-sh, the careers page, the press." + STATEMENTS_PLAN;

    public static final String ORGANIZATION_CRITIC = " The people in power — the chair, the chief executive, the owners, the founders — each have a "
            + "dated decision on record with the reason given, or a search that found none; a chair or a chief executive with no decision is a gap, "
            + "and so is a work with no named customer, no named peer or alternative and no search that found none." + STATEMENTS_CRITIC;

    public static final String PERSON_PLAN = "Two more sub-questions. Influences: who and what shaped the person, as they said it themselves "
            + "(interviews, talks, acknowledgements, the books and people they name), and whom they shaped — who cites, credits or followed them "
            + "(openalex-works for citations, google-books, youtube and podcasts for interviews). Decisions: the principal moves of the career — a "
            + "founding, a departure, a sale, a stand taken — dated, with the reason the person gave for each in their own words where the record "
            + "quotes them, who else was involved and what came of it." + STATEMENTS_PLAN;

    public static final String PERSON_CRITIC = " The principal moves of the career each carry the reason the person gave, or a search that found none." + STATEMENTS_CRITIC;
}
