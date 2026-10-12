package org.researchzosho.librarian.profiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.researchzosho.librarian.LibraryStore;
import org.researchzosho.librarian.Researcher;
import org.researchzosho.librarian.Shelf;
import org.researchzosho.librarian.dna.Dna;
import org.researchzosho.librarian.dna.LikeX;
import org.researchzosho.librarian.dna.Prefs;
import org.researchzosho.tools.BookSearchTool;
import org.researchzosho.tools.ContentPolicy;
import java.io.IOException;
import org.researchzosho.librarian.Profile;
import org.researchzosho.librarian.Vocabulary;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The books field (0.6.0): a question about which book to read, what a book covers, or what to read next. Its rules keep the run to real
 * books — only what {@code book_search} returned may be named, each cited by its record — send the workers past the bestsellers to subject
 * headings, reading lists, syllabi and "further reading" sections, and make the table say fit, never rank. It joins a run by the question's
 * words; the tool is in every run. The design and its evidence are in docs/REC_BOOKS.md.
 */
public final class BooksProfile implements Profile {

    @Override public String name() { return "books"; }

    @Override public String description() { return "which book: real books from the catalogues, found by subject, lists and syllabi rather than sales, judged by fit to the ask or to the person's own shelf"; }

    @Override public List<Vocabulary.Term> predicates() {
        return List.of(
                new Vocabulary.Term("wrote", "wrote", List.of("is the author of", "authored"), ""),
                new Vocabulary.Term("covers", "covers", List.of("is about", "treats", "introduces"), ""),
                new Vocabulary.Term("recommended-in", "is recommended in", List.of("appears on the list", "is assigned in", "is cited as further reading in"), ""));
    }

    /** Words that mean the person is choosing something to read. */
    static final List<String> WORDS = List.of(
            "which book", "what book", "a book on", "a book about", "books on", "books about", "book recommend", "recommend a book", "recommend books", "what to read", "what should i read",
            "reading list", "textbook", "novel", "novels", "good read", "worth reading", "introduction to", "primer on", "best book", "読む本", "本を", "おすすめの本", "書籍", "小説", "読書", "入門書", "教科書", "何を読め");

    @Override public boolean applies(String question) {
        String q = Vocabulary.norm(question);
        for (String w : WORDS) if (q.contains(w)) return true;
        return false;
    }

    @Override public String register() {
        return "This is a question about books, and the run names only real ones: a title may appear in the answer only if book_search returned it in this run, "
                + "and it is cited by its record link (Open Library or NDL Search). Find candidates by subject heading (book_search kind=subject, plain headings as Open Library spells them) and by author "
                + "as well as by words — for \"what should I read next\", book_search kind=shelf first, which pools the catalogue under the subjects the person "
                + "rated highly with what they own left out — and past the bestsellers: search the web for reading lists, syllabi, society and library lists, and the \"further "
                + "reading\" sections of long reviews and textbooks, then look each title up in the catalogue before it counts. Read a work's record "
                + "(kind=work) for what it covers; for a Japanese book, openBD's 内容紹介 and 目次 by ISBN. Judge fit to the ask — the subject, the level, the "
                + "year and edition, the length, the language — never rank by ratings, review counts or edition counts, which are shown as cues. Where "
                + "the person owns a book already (my_shelf kind=have, for every candidate; the holdings tool for a list), say so and do not recommend it again; "
                + "for \"what should I read next\" the profile is my_shelf kind=profile and the books to be like are my_shelf kind=liked. For a book out of print or "
                + "hard to find, product_search kind=marketplaces books=true gives where to look (second-hand bookshops, eBay, the Japanese marketplaces) as links. Candidates come from this run\u2019s catalogue "
                + "and list searches; an earlier report in the library is background, and its titles count once this run\u2019s catalogue returns them. Your own "
                + "memory of books is not a source: a title you remember but the catalogue did not return in this run is not named.";
    }

    @Override public String planRules() {
        return "This is a books question. Restate the ask as the criteria the table will judge by: the subject and how deep, the level the person is at, "
                + "the language, what they have read already. Then one sub-question gathers candidates from the catalogue by the person\u2019s liked subjects and by "
                + "heading (book_search kind=shelf, then kind=subject for each heading the ask names), one gathers candidates from reading lists, syllabi and further-reading "
                + "sections on the web (then looked up in the catalogue), and one sub-question per shortlisted book, eight at most, reads its record and "
                + "what reviews or tables of contents say it covers.";
    }

    @Override public String criticRules() {
        return "For a books question the evidence is enough when every title in the table came from book_search in this run with its record link, has its "
                + "subjects and year from the record, and has at least one source beyond the record (a review, a list it appears on, a table of contents) "
                + "or is marked as standing on the record alone; a title with no catalogue record is dropped, and so is a row the writer itself says falls short "
                + "of the ask\u2019s core criterion — fewer rows beat padding. A catalogue candidate not on the shelf that fits the ask better than a row in the "
                + "table is a gap to name. A gap worth another search names a subject heading, a list, or a level not yet covered.";
    }

    @Override public String writerRules() {
        return "Open with the ask as restated. Then the table, eight rows at most: the book (title and author, linked to its record), the year and edition, "
                + "what it covers in the ask's terms, who it is for (the level, from the list or review it was found on), the length, where it was found "
                + "(the heading, list or review), and the one-line reason it fits. A book that falls short of the ask\u2019s core criterion is not a row: four rows "
                + "that fit beat eight with padding. Prefer a catalogue candidate that fits over a list-famous one that does not. No \"best\"; say which "
                + "criterion each row wins on. What the person\u2019s shelf says (their ratings, their authors) is written as \"your shelf: …\" with no source "
                + "number — a citation is for what a page or a record says. Ratings and edition counts, where the record has them, are a column of cues. After the table: the lists and reviews worth "
                + "reading, as links, and what could not be verified. Japanese books cite NDL Search records and openBD.";
    }

    /**
     * The candidate pool from the person's shelf, made before the workers start: the works of the authors they rate well that the shelf
     * lacks, then the liked subjects for breadth — what the second and fourth live runs showed the model would not always ask for itself.
     * Recorded on the run's policy, so the write-up's title check knows these books.
     */
    @Override public String runEvidence(LibraryStore store, Researcher.Ask ask, ContentPolicy policy) throws IOException {
        if (Shelf.path() == null) return "";
        StringBuilder out = new StringBuilder();
        String brief = Prefs.load(Dna.BOOK).brief();
        if (!brief.isEmpty()) out.append("THE PERSON'S PREFERENCES FILE (their own lines; the table honours them): ").append(brief).append("\n\n");
        String likeTitle = likeTitle(ask.question());
        if (!likeTitle.isEmpty()) {
            LikeX.OnShelf o = LikeX.onShelf(likeTitle, "", 10, true);
            if (o.ok()) out.append("BOOKS ON THE SHELF LIKE \"").append(o.book().title()).append("\" (from the library's DNA store, by each book's text and the model's reading of it, not from memory; the author's own works set aside; a \"like X\" answer draws from these first, each checked with my_shelf kind=have):\n").append(LikeX.text(o.result())).append("\n\n");
            else if (o.book() != null) out.append("THE SHELF HAS \"").append(o.book().title()).append("\" BUT ").append(o.message()).append("\n\n");
        }
        try {
            String subjects = BookSearchTool.askedSubjects(ask.question());   // "space opera" from "what should I read next in space opera", as the shelf spells it
            String pool = new BookSearchTool().policy(policy).execute(new ObjectMapper().createObjectNode().put("kind", "shelf").put("limit", 60).put("query", subjects));
            if (!pool.startsWith("No Calibre") && !pool.startsWith("The shelf has no rated"))
                out.append("WHAT THE SHELF SAYS TO START FROM (made by the library from the catalogues, not by a model; a \"what next\" answer draws from these first, "
                        + "each cited by its record link; book_search kind=shelf with query=<a subject> narrows them, kind=work reads one):\n").append(pool);
        } catch (Exception e) { /* the pool is a help, never a requirement */ }
        return out.toString().strip();
    }

    static final Pattern LIKE = Pattern.compile("(?i)\\b(?:like|similar to|in the vein of|reminds me of|along the lines of|more like)\\s+[\"“]?([^\"”,.;?]{3,80}?)[\"”]?(?:\\s+by\\s+[^,.;?]+)?(?:[,.;?]|$)");

    /** The title a question asks for books like, when it names one: the words after "like", "similar to" and the rest, up to the end or a stop. */
    public static String likeTitle(String question) {
        if (question == null) return "";
        Matcher m = LIKE.matcher(question);
        while (m.find()) { String t = m.group(1).strip(); if (!t.isEmpty() && !t.matches("(?i)(this|that|these|those|it|them|(this|that|the) (one|last|first|other)s?( ones?)?|mine|my .*|what .*|something|anything|the same)")) return t; }
        return "";
    }

    @Override public String chatNote() {
        return "A books question (\"which book on X\", \"what should I read next\", \"おすすめの本\") is a conversation before it is a run: restate what they are after "
                + "in one line, ask what you cannot infer, one question at a time — the level, the language, what they have read on it already (library_my_shelf "
                + "op=have for a title, op=liked for a subject; library_holdings for a list) — then file the run with every constraint in words. After the report, serve \"more like "
                + "this one, less like that\" from the report's own table first, re-cut by the new constraint; a narrow follow-up run only for what the run "
                + "never looked at. Never a title outside a report's table.";
    }
}
