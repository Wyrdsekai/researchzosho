package org.researchzosho.librarian.profiles;

import org.researchzosho.librarian.Crafts;
import org.researchzosho.librarian.LibraryStore;
import org.researchzosho.librarian.Profile;
import org.researchzosho.librarian.Researcher;
import org.researchzosho.librarian.Vocabulary;
import org.researchzosho.tools.ContentPolicy;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The crafts field (0.6.1, docs/AFTER_0_6.md §1): a question about doing or making something — a craft, a hobby, a sport, a martial art, a
 * trade, a repair, a build — and what one needs to do it. What it adds is the kit: a research answer about doing carries a bill of materials, each thing needed with what
 * it is for, what substitutes for it, and where to get it, the recipe field's ingredients-with-substitutes generalised to any craft; and
 * for a historical way of doing something, how it was done, how to do it now, and the modern stand-in for each historical material with
 * the reason and the source. It joins a run by the words of doing, and anchors the ask to the kinds of doing the library's list names
 * ({@link Crafts}: Wikidata's trees, Wikipedia's outline, Japan's designated crafts, the Red List), telling the run what is known of them.
 */
public final class CraftsProfile implements Profile {

    @Override public String name() { return "crafts"; }

    @Override public String description() { return "doing and making: any craft, hobby, sport, martial art, trade or community people join, with the kit — what one needs, what its rules demand, how it goes together, how to make it today, where to learn, what substitutes, where to get it"; }

    @Override public List<Vocabulary.Term> predicates() {
        return List.of(
                new Vocabulary.Term("needs", "needs", List.of("requires", "is made with", "calls for"), ""),
                new Vocabulary.Term("substitutes-for", "substitutes for", List.of("stands in for", "replaces", "can be used instead of"), ""),
                new Vocabulary.Term("technique-of", "is a technique of", List.of("is a method in", "is used in"), ""),
                new Vocabulary.Term("practised-in", "is practised in", List.of("is made in", "is a craft of"), ""));
    }

    /** Words that mean the person wants to do or make something, or needs the things for it: phrases of making, never a bare noun ("craft" is in "aircraft", "tools" in every field). */
    static final List<String> WORDS = List.of(
            "how to make", "how do i make", "how can i make", "how to build", "how do i build", "how to weave", "how to knit", "how to sew", "how to carve", "how to forge", "how to cast", "how to dye", "how to braid", "how to replicate",
            "how do i weave", "how do i knit", "how do i sew", "how do i carve", "how do i forge", "what do i need to", "what do you need to", "what is needed to", "what tools do i need", "what materials do i need", "which tools do i need",
            "supplies for", "materials for", "a kit for", "the kit for", "pattern for", "patterns for", "knitting pattern", "sewing pattern", "crochet pattern", "weaving draft", "woodworking plan", "make my own", "making my own",
            "do it yourself", "handicraft", "handicrafts", "作り方", "作るには", "必要な道具", "必要な材料", "手作り", "工芸", "手芸", "編み方", "織り方");

    /** The words of taking something up — a sport, a martial art, a trade: what one needs, how it goes together, where to learn. They count only
     *  with a kind the list names ("how do I get into PRS"), since "how do I start" alone opens a family tree as readily as a sport. */
    static final List<String> TAKING_UP = List.of(
            "how to start", "how do i start", "how can i start", "how to get into", "how do i get into", "how can i get into", "how to take up", "want to take up", "learn to do", "want to learn",
            "what do i need for", "what equipment", "equipment for", "equipment do i need", "gear for", "gear do i need", "what gear", "a beginner in", "for a beginner",
            "how to train for", "how do i train for", "how to compete in", "how do i compete in", "where can i learn", "where do i learn", "where to learn", "how to put together", "how do i put together",
            // a community: joining it, the kit its rules demand, making or bettering that kit
            "how to join", "how do i join", "how can i join", "want to join", "joining the", "join the", "a member of", "better equipment", "better gear", "upgrade my", "improve my kit", "my own gear", "my own equipment",
            "build my own", "make my own", "pass inspection", "legal for", "allowed in", "allowed under", "the rules allow", "minimum armour", "minimum armor", "armour standards", "armor standards",
            "始め方", "始めるには", "入門", "必要な装備", "装備は", "習うには", "習い方", "道具一式", "入会", "参加するには", "自作");

    @Override public boolean applies(String question) {
        String q = Vocabulary.norm(question);
        for (String w : WORDS) if (q.contains(Vocabulary.norm(w))) return true;
        for (String w : TAKING_UP) if ((q.contains(Vocabulary.norm(w)) || question.contains(w)) && !Crafts.find(question).isEmpty()) return true;
        return false;
    }

    /** A question that names a kind of doing without a word of doing: offered, not taken. */
    @Override public boolean suggests(String question) { return applies(question) || !Crafts.find(question).isEmpty(); }

    @Override public String register() {
        return "This is a question about doing or making something. Answer the how, and with it the kit: every material and tool needed, each with what "
                + "it is for, what substitutes for it, and where to get it (product_search, with the vintage and second-hand marketplaces for the old and the rare; a named "
                + "shop or maker only as a source says it). A material is named by its working properties (the fibre and its weight, the wood species, the alloy, the "
                + "gauge), never by a brand alone. For a historical way of doing something, three things in order: how it was done (museum records, conservation and "
                + "laboratory reports, scanned manuals, experimental archaeology), how to do it now, and for each historical material the modern stand-in with the reason "
                + "and the source that gives the property it must match. A pattern, a plan or a draft is a source: cite it by its address and say its terms where the "
                + "page states them. Where a craft is designated or endangered, say so and by whom, with the link. When the kind is a sport, a martial art or a trade, the kit "
                + "is the gear by its role in the order a beginner needs it, each with the specification that must fit the person or the class rules (the draw weight, the "
                + "blade length for the height, the calibre and the rifle class, the wetsuit for the water), what substitutes and where to get it; how it goes together — "
                + "the build, the set-up, the fit — from the governing body's rules and the makers' and clubs' own pages; where one learns (a club, a dojo, a range, a "
                + "federation's beginner course) and what the law requires (a licence, an age, insurance) from the authority's own page in the person's country; and the "
                + "crafts that make the kit, when the list names them. When the kind is a community — a society, a federation, a reenactment group, a game system — "
                + "the answer is, in order: what it is and how to join (the local branch, the newcomers' page, the first event and what to bring, the fees), its rulebook "
                + "and standards for the equipment and the activities it allows (the marshal's or armourer's rules, the inspection, the dimensions and materials "
                + "allowed, cited from the community's own pages), the kit those rules demand by activity, how its members make their own (the makers' forums and "
                + "guides, the plans and patterns, the materials and tools, the modern ways the rules allow — plastics, foam, 3D printing, laser cutting, modern "
                + "steels — with the rule that allows each), what to buy from whom (the merchants the community uses, second-hand), and for someone already in it "
                + "the next step in kit: what the experienced use and what the rules allow. What a page says is evidence; what you remember is not.";
    }

    @Override public String planRules() {
        return "This is a crafts question. Restate the ask as the thing to be made or done, the level the person is at, and what they have already. Then one "
                + "sub-question gathers how it is done (manuals, patterns, the makers' own pages, museum and laboratory records for a historical way), one gathers the kit "
                + "(every material and tool, with what it is for and what substitutes), and one finds where to get each thing, the old and the rare on the second-hand "
                + "marketplaces. For a historical case, one sub-question per historical material: its modern stand-in and the reason. For a sport, a martial art or a "
                + "trade, the sub-questions are: the discipline and its rules (the governing body, the rulebook, the classes, Olympic or not); the kit by role with the "
                + "specifications that must fit the person; how it goes together; where to learn and what the law requires in the person's country; and the ladder from a "
                + "first session to competition or qualification. For a community, the sub-questions are: what it is and how to join (the local branch, the newcomers' "
                + "page, the first event); its rulebook and equipment standards for the activity asked about; the kit those standards demand; how members make their "
                + "own and what modern materials and methods the rules allow; where members buy; and, for someone already in it, the next step in kit.";
    }

    @Override public String criticRules() {
        return "For a crafts question the evidence is enough when the how comes from a maker's, a manual's or a museum's own page; when every material and tool in the kit "
                + "has its purpose and a substitute from a source, and a modern stand-in with a reason where the material is historical; and when the places to get them are "
                + "pages a person can open. A kit item with no source is a gap to name, and so is a material named only by a brand. For a sport or a martial art, the kit "
                + "comes from the governing body's equipment rules or a maker's or a club's page, the law from the authority's page in the person's country, and a place "
                + "to learn is one a person can write to. For a community, the rules come from its own rulebook or standards page, never from memory; a kit item "
                + "the rules govern cites the rule; a making guide is a member's or a maker's own page.";
    }

    @Override public String writerRules() {
        return "Open with the ask as restated and the level it is written for. Then the how, in steps a person can follow, each cited. Then the kit as a table: what "
                + "(the material or tool), what it is for, how much or which size, what substitutes for it, where to get it (as links), and for a historical material the "
                + "modern stand-in and why. Say what is designated or endangered, and where a pattern or a plan may be used. For a sport or a martial art: the discipline "
                + "in two lines (what it is, who governs it, Olympic or not), the kit table in the order of need with a column for what must fit, how it goes together, "
                + "where to learn and what the law requires, then the ladder. For a community: what it is and how to join, then the rules the equipment must meet "
                + "(each with its citation), then the kit table by activity (what, the rule it meets, make or buy, where), then how to make it with today's "
                + "materials and the rule that allows each, then where members buy, then the next step for someone already in it. A rule the notes carry about "
                + "the equipment is never left out: a branch's, a kingdom's or a club's rule that tightens the society's goes beside it, cited, as \"your kingdom "
                + "may require …\". A gauge is converted with the table given in the evidence and never computed; a rule that gives both a gauge and a "
                + "millimetre figure is cited as it says it. Close with what could not be verified.";
    }

    /** Sheet-metal gauge to millimetres, for the writer to copy and never compute: the SCA report of 2026-10-13 wrote 1.98 mm for 14 gauge. */
    static final String GAUGE_TABLE = "GAUGE TO MILLIMETRES (copy from here, never compute). Steel sheet, Manufacturers' Standard Gauge: 10 ga 3.42 mm, 11 ga 3.04 mm, 12 ga 2.66 mm, "
            + "14 ga 1.90 mm, 16 ga 1.52 mm, 18 ga 1.21 mm, 20 ga 0.91 mm, 22 ga 0.76 mm. Aluminium, Brown & Sharpe: 12 ga 2.05 mm, 14 ga 1.63 mm, 16 ga 1.29 mm, 18 ga 1.02 mm. "
            + "A rule that gives both a gauge and a millimetre figure is cited as it says it, even where the two differ from this table.";
    static final Pattern GAUGE_ASK = Pattern.compile("(?i)\\b(gauge|ga\\b|armou?r|helm|sheet metal|sheet steel|plate)");

    /** What the library's list of kinds knows of the kinds the ask names, put before the workers start; and the gauge table when the ask is about armour or sheet metal. */
    @Override public String runEvidence(LibraryStore store, Researcher.Ask ask, ContentPolicy policy) throws IOException {
        List<Crafts.Kind> kinds = Crafts.find(ask.question());
        String gauge = GAUGE_ASK.matcher(ask.question()).find() ? GAUGE_TABLE : "";
        if (kinds.isEmpty()) return gauge;
        StringBuilder b = new StringBuilder("THE KINDS OF DOING THIS ASK NAMES (from the library's list of crafts, hobbies, sports, martial arts, trades and the communities people join — Wikidata's trees, Wikipedia's outline of crafts, its lists of sports, shooting sports and martial arts, its categories of reenactment, LARP and HEMA groups, ISCO-08's trades, Japan's designated crafts, the Heritage Crafts Red List; a discipline is listed under its sport, a community under what its members do; a fact to cite is on the page linked, not here):\n");
        for (Crafts.Kind k : kinds) b.append(Crafts.describe(k)).append('\n');
        if (!gauge.isEmpty()) b.append('\n').append(gauge).append('\n');
        return b.toString().strip();
    }

    @Override public String chatNote() {
        return "A question about making or doing something (\"how do I weave a tapestry\", \"what do I need to start knitting\", \"作り方\") is a conversation before it is a "
                + "run: restate what they want to make or do, ask what you cannot infer, one question at a time — the level, what they have, whether the old way or today's, "
                + "and for a sport or a martial art their country (the law and the clubs differ); for a community, whether they are joining, in it and wanting better "
                + "kit, or making their own — then "
                + "file the run with every constraint in words. The report carries the kit; serve \"what can I use instead of X\" from the report's own table first.";
    }
}
