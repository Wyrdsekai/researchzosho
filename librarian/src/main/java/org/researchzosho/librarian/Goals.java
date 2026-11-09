package org.researchzosho.librarian;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The goals this build knows, one of each, read off the fields that back them ({@link Profile#goalName}) and the generic goal, which
 * is the basis alone. A goal's words, instruments, shape and decision rules are data here; its plan, critic and writer rules are the
 * backing fields' during the transition. {@link #forQuestion} gives the goals whose words apply, with their reasons, for the brain to
 * weigh; the generic goal applies when no other does.
 */
public final class Goals {

    private Goals() { }

    /** What a goal is beyond its fields: the words on the table, the instruments it brings, the shape, the decision rules, and the words that say it applies. */
    record Spec(String name, String words, List<String> instruments, String shape, String decisionRules, Pattern applies, String why, boolean joinsByWords) { }

    static final List<Spec> SPECS = List.of(
            new Spec("home",
                    "Looking for a home to buy or to rent. It pays when the question asks for homes, flats, houses or listings in a place with a budget, rooms or a size. Example: \"find homes for sale in Cambridge under $800 a square foot with 3 bedrooms\".",
                    List.of("homes", "records"),
                    "Tiers of fits, best first, then near misses: each home one line with its address, price, size, rooms, the price by the unit the person asked by, its source and a link to its page; a card for the best few with the records, the flood risk, the schools and the transit, each figure with its source.",
                    "A home is found with homes, never from web prose about a market; a home's values, sales and permits come from records; a listing's figures are read from its own page and the record line says which source answered; a feature of a home is checked with its page or records, never read off a snippet of a list page; how well a home fits the person is judged by the dossier builder's run on it, its card, pursued as dossier when it matters, never by an impression of a list page.",
                    Pattern.compile("(homes?|houses?|apartments?|flats?|condos?|places?|propert(y|ies)|listings?|rentals?|units?) (for sale|for rent|to rent|to buy|to let|on the market)|(for sale|for rent|to rent|to let) (in|near|around)|(buy|rent|find|looking for|look for|search for) (a |an |some |the )?(home|house|apartment|apartments|condo|flat|place to live|rental)|(square|sq\\.? ?) ?(feet|foot|ft|meters|metres|m)|\\b\\d+ ?(bedrooms?|beds?|bd|bathrooms?|baths?|ba)\\b|(zillow|redfin|realtor\\.com|rightmove|zoopla|suumo)|賃貸|物件|マンション|間取り|戸建て|中古住宅|家を買|家を借り|住まい探し"),
                    "the question asks for homes, a place to live, or a listing's figures",
                    true),
            new Spec("work",
                    "Looking for work: openings, employers and boards for a person's working life and what they want next. It pays when the question asks for jobs, openings or employers in a place or a field. Example: \"find staff engineer openings near Boston at companies that allow remote work\".",
                    List.of("board"),
                    "The openings that fit, best first: each one line with the title, the employer, the place, where it was seen, the date and a link to the posting; then the employers worth watching, each with its board; then what was set aside and why. The answer names only openings read from their own postings, says which boards were read whole and which could not be read, and says of each thing the person asked for, remote, pay, level, whether the posting states it or it was not checked; an opening the run did not read is not a fit.",
                    "The pool of employers is enumerated, never an engine's first page: the registers of the kind in the place (search where=records for the hospitals, schools, public bodies and firms a register lists), the employers the person named, their own boards and the public feeds; a search engine's hit is a lead whose board is then read, never an opening in itself. An opening is read from the employer's own board or posting with board, which lists the board whole and cuts it by the title words and the town, never asserted from a page about the company and never taken from a board's featured, top or sponsored slice; an employer named by research is checked by its board before any opening of it is kept; a feature of an opening, remote, pay, level, is read from the posting itself; the openings are ordered by fit to what the person asked, never by a board's own order; how well a company fits the person is judged by the dossier builder's run on it, pursued as dossier with the company's name, never by the model's own impression.",
                    Pattern.compile("\\b(jobs?|openings?|vacanc(y|ies)) (for|as|in|at|near|with|that)\\b|\\b(positions?|roles?) (for|as|at) (a|an|the|senior|junior|staff|lead|principal|new)\\b|\\b(find|looking for|look for|search for|searching for|want|need) (a |an |some |the |my )?(next |new )?(job|jobs|work|position|role|openings?|employer|employers)\\b|job (search|hunt|hunting|posting|postings|board|boards|listing|listings|offers?|openings?|market)|careers? (page|site|board)|\\b(my|a|your|the|his|her) (resume|résumé|cv)\\b|\\blinkedin\\b|employers? (hiring|who|that|in|near|worth)|\\b(is|are|who's|who is|now|currently|still|companies|clinics|hospitals|firms|employers|anyone|anybody) hiring\\b|\\bhiring (now|near|in|for)\\b|求人|転職|採用情報|就職|仕事を探|職を探|働き口|求職|募集中|採用ページ|仕事を見つけ"),
                    "the question asks for jobs, openings or employers",
                    true),
            new Spec("dossier",
                    "A dossier on a person or an organisation: identity, timeline, roles, holdings, filings, people, money, marks, footprint and public record, each claim with the class of its source. It pays when the question names one person or one organisation and asks what is known. Example: \"everything on record about Verkada\".",
                    List.of("dossier"),
                    "The dossier's sections: identity, timeline, roles, holdings, associations, public record, what is current; every claim with its source class.",
                    "The major things first: for an organisation its founding (the year, the place, the name it was founded under), its charter or incorporation and the milestones before any other facet; for a person born (year, place), educated, the roles in order; each from a register or the subject's own record before a page about them. A fact about a person or an organisation comes from a register, a filing or the subject's own record; the dossier instrument builds it; a claim's class is said. Example: pursue(goal=\"dossier\", subQuestion=\"Who is Acme Corp? Founded when, where and under what name, chartered when; then the registry card\").",
                    null, "the question names a person or an organisation and asks what is known of them", true),
            new Spec("buy",
                    "Buying a thing: candidates for sale now in the person's own market, each checked for fit, specs, recalls, repair and owners' words; fit, never rank. Example: \"which cordless drill under $150 for a home workshop in Japan\".",
                    List.of(),
                    "A table of six candidates at most: the product as the shop writes it with a link to the listing, what it does for the need, the price with its currency, shop and time, the datasheet, recalls, repairability, what owners say, and the one-line reason it fits or loses. The answer says of each candidate which checks were made, the listing read, the recall feeds asked, the datasheet found, the owners' words read, and which were not; a notice on file is quoted with its date and its link, and where none was found the words are \"no notice on file\", never safe; a shop the library could not read is a link, never a price; and where no candidate meets every constraint the answer says which constraint gives on each side rather than hiding one.",
                    "The candidates are enumerated from the market's shops within the budget and whatever else a machine can check, the price, in stock, the size or the fitting the person named, paged past the first page (search where=shops, or product_search kind=shops with price_max and page), never a shop's best sellers, top rated, sponsored or choice slice, and never in the shop's own order, which is popularity and advertising; where no shop in the market answers without a key, a web hit is a lead whose own listing page is then opened for the price and the time, never a candidate from a review or a list page alone. A candidate comes from a shop's listing with a price and a time, never from a review alone; each is asked of the recall sources with product_safety before it goes in the table; the datasheet comes from product_search kind=specs, what owners say from kind=owners in the owners' own words, the repair score from kind=repair; a review site whose terms forbid reading is pointed at, never read; ratings, review counts, badges and a brand's name are cues to mention, never a reason; the market is the person's own unless they name another; a thing the person owns is not proposed again.",
                    null, "the question asks what to buy", true),
            new Spec("prior-art",
                    "Patents and prior art: whether something like an invention was already filed or granted, by whom and when, and what a patent read by its number claims, with its dates, assignee, family and status. It pays when the question asks whether an idea is new, what was patented before it, or what a numbered patent covers. Example: \"has anyone patented a dog bowl that folds flat and doubles as a throwing disc, and what does US 10,000,000 claim\".",
                    List.of("patents", "patent_abstracts", "patent_claims", "search"),   // the pool enumerated from the office's own records, its abstracts read, the claims read from the office's full text; search for the class
                    "First the answer, bound to what was read: for each feature of the invention the ask named, the closest publication whose claims were read, by its number with the office's link on the line, the number of the claim and its words quoted; each quoted claim says where it was read: the office's own full text, a named member of the family, or Google Patents' copy of the office's record where the office's service holds no claims text; a publication with claims at neither is quoted from its abstract and said to be an abstract; a feature no claim read meets is said to be met by none that was read, which is not the same as none that exists. Then the counts, each as the readings said it: the classification and the years, the office's own count of publications, how many families were enumerated, how many abstracts were read, how many families' claims were read from the office and how many from Google Patents' copy, how many by their abstracts alone. Then what was not read, one line each: the abstracts not read, the families that pass whose claims were not read, the publications whose claims the office's service does not hold, the subgroups under the class, and the other classes, the years, the offices and the languages not searched. Then the candidates in a table, oldest first: number, title, applicant, the earliest date as its line gives it, the claim that matters, why it fits; a date or an applicant no line gives is left out, and a publication whose claims were not read is listed as unread. Nothing is said of a patent beyond what its own record showed; whether it was granted, is pending or has lapsed is said only in the words of the source that says it, as that source's reading (\"Google Patents words its status as Pending\"), or left out.",
                    "Prior art goes in one order: the class, then patents, then patent_abstracts, then patent_claims. First find the classification the invention sits in, a CPC group with its slash such as A47G19/22: from the office's own classification (search where=records source=epo-classification with the thing in plain words gives the groups and the subgroups under them with their titles; the class to give is the subgroup the invention sits in) or from the classes on a known patent's record (patent_claims with its number in publications prints them). Then patents, called with the classes, the years (those the ask gives, else the twenty years before the date that matters) and the invention's features, each a short phrase with the words and synonyms that would name it in a title or an abstract, in every language you can give, such as {\"classes\": [\"A47G19/22\"], \"from_year\": 2005, \"to_year\": 2015, \"features\": [\"lid, cover, cap, Deckel, 蓋\", \"straw, drinking tube, Trinkhalm, ストロー\"]}, never a query: it enumerates for itself every publication of each class in those years from the office's own records, page by page to the end, says the office's own count, keeps one line a family and hands back the pool under a key, ordered by the features named and never by citations, family size or an engine's rank. Then patent_abstracts with that key in from reads every family's abstract, twenty to a call, and says of each feature yes, maybe or no; when it says some were not read, the same call with page=2 goes on. Then patent_claims with that key in from reads the claims of the families that pass, best first, from the office's own full text, and quotes for each feature the claim that comes closest; where the office's service holds no claims text for a family, which is so for the United States, China, Japan and Korea, it reads the claims from Google Patents' copy of the office's record at the publication's own address, and says which claims came from where; a patent's page is never opened by you for its claims or its status, since patent_claims has read it or says why it could not. The answer rests on the claims quoted: a feature is met only by a claim quoted, and a feature no claim read meets is said to be met by none that was read, with the office's count, the families enumerated, the abstracts read, the claims read and what was not read beside it. A patent named by its number is read with patent_claims and its number in publications. A run of this kind has no patent search that ranks on its table: patents is the pool.",
                    Pattern.compile("(already|previously|before) (been )?(patented|invented|filed)|(is|are|was) (my|this|that|the|our) (idea|invention|design|device|gadget|mechanism|contraption) (new|novel|patentable|already)|has (anyone|anybody|someone|somebody) (patented|invented|filed|claimed)|(novelty|freedom[- ]to[- ]operate|clearance) (search|check|opinion)|(can|could) (i|we) (patent|get a patent)|新規性|進歩性|既に(特許|発明|出願)|(すでに|既に)(誰か|だれか)|特許(を|は)?(取れ|取得でき|とれ)|発明は新しい|先行(技術|文献)"),
                    "the question asks what was patented or invented before, or what a patent claims", true),
            new Spec("what-the-law-says",
                    "What the law says: statutes, regulations, court decisions and ordinances, the primary texts first, each claim with its jurisdiction and the date the law stood. It pays when the question asks what a law requires, allows or forbids, what a court decided, or whether something is legal where the person is. Example: \"what does Massachusetts law require of a landlord before raising the rent\".",
                    List.of("search", "open"),   // the jurisdiction's own collections through search in records (courtlistener, ecfr, federal-register, egov-laws, egov-keyword), the text read whole with open
                    "The first sentence answers in the jurisdiction's own terms, naming the governing text by its own citation, linked on the line, and the date it stood. Then each level of law in its own paragraph, federal, state, municipal, or 国, 都道府県, 市町村: the operative words quoted from the text, then the decisions that read it, each by citation and date, then what has changed or is pending. Then what was not checked, one line each: a level not searched, a court not searched, a date past which the text may have changed. Nothing is stated as the law that was not read from a primary text; a claim resting on commentary alone says so. Research, not advice: what the texts say and who decided what, the person's own case left to them.",
                    "The primary text is the candidate: the statute's section, the regulation, the decision or the ordinance, found through the jurisdiction's own collections (search in records: courtlistener, ecfr and federal-register for the United States, egov-laws and egov-keyword for Japan) or fetched by the citation the question or a page gives, and read whole with open; a web search finds the citation, never the rule. A decision is checked against the text it reads and against the later decisions that cite it, each by the date it stood, the latest controlling; a level or a court not searched is said as not searched. Commentary is a path to the text and is cited only for what it says of the text.",
                    Pattern.compile("(is|are|was|were) (it|this|that|they|he|she) (legal|illegal|lawful|unlawful)|against the law|(am i|are we|is (he|she|one|it)|are they) (allowed|required|obliged|permitted|entitled) (to|by law)|(can|may|must|does|do|should|will) (my|the|a|an|our|your) (landlord|tenant|employer|employee|boss|neighbou?r|city|county|state|council|school|police|hoa|homeowners association|bank|insurer|contractor) (legally )?(require|demand|refuse|evict|deduct|withhold|fire|charge|ban|forbid|make|keep|take|raise|enter|record|search|sue|owe|return|withdraw|cancel)|what (notice|deposit|licen[cs]e|permit|penalty|fine) (is|does|do|must|applies)|required by law|by law\\b|what does the law|what the law (says|requires|allows)|\\b(law|laws|statutes?|code|ordinances?|regulations?) (require|requires|required|say|says|allow|allows|forbid|forbids|permit|permits|prohibit|prohibits|let|lets)\\b|(what|which) (state|federal|local|municipal|city|japanese|us|uk|eu) (law|laws|rules?|regulations?)|違法|合法|法的に|法律上|法律で|条例で|義務がある|義務付け|許可が必要|届け出が必要|違反に(なる|なり)"),
                    "the question asks what the law requires, allows or forbids, or what a court decided", true),
            new Spec("family",
                    "Building the family: who is who, the lives of its people, the names each carried over a life, the records that tie a person to their family, GEDCOM in and out. A module: it joins when the person asks for it, with /family, with the family goal or the field by name, and never by a question's words alone. Example: research ask \"who were the parents of Tom Hale of Leeds, who died in 1901, and what did he do for a living\" --goal family.",
                    List.of("who", "search", "shelf"),   // who is who from the family's own account; the registers and the collections through search in records; the family's own shelves
                    "Opens with Answers: each numbered question of the brief in one of three ways and no other: answered by a record, with the record linked on the line; not found, with the collections, the name forms and the years looked in; or the records disagree, with both sides. Then each life in order of date, one line an event, under the name the person carried at that date, the later name in brackets the first time; then Identity: which record was tied to which person and by what second identifier, and the candidates that could not be tied, named as candidates; then the records only the family can request, and how. Nothing is said of a namesake as of the person, and nothing of a person beyond what a record or the family's own account gave.",
                    "A person is identified by the family's own account before any search: who answers who they are and what the family said of them, and the search starts from that. A record is tied to a person by a second identifier the family gave, a parent, a spouse, a place, a trade, an age, never by the name alone; a name is searched under every form the person carried in each period of their life, and a record under a name the person did not carry then is a question about when the name changed, not a fact. What the person did, the trades, the moves, the offices, the public events, is sought as much as when they were born and died. A tree site or an index is a clue, followed to the record it points to; a collection searched and found empty is noted with the name forms and the years.",
                    null, "the family module joins only when somebody asks for it; a question that looks like family history is told it is there", false),
            new Spec("learn-a-craft",
                    "Doing and making: a craft, a hobby, a repair, a build: what one needs, how it goes together, how to make it today, where to learn, what substitutes. Example: \"how do I make a canvas tote bag with an inside pocket\".",
                    List.of("look"),
                    "The how and the kit: every material and tool with what it is for, what substitutes for it and where to get it; the steps; where to learn; for an old way, how it was done, how to do it now and the modern stand-in for each material. Each step and each item of the kit names the instruction, pattern, manual or video it was read from; a step or an item with no source read is said to stand unverified; the answer says which instructions were read whole, which videos were looked at and which were only described, and names first what the person asked for that was not checked.",
                    "The instructions are enumerated from makers' own pages, patterns, manuals, a governing body's rules, museum and laboratory records and videos of the making, and judged by fit to the thing, the level and what the person has, never by views, likes, subscribers or a tutorial's rank on the engine. A step or a material comes from a maker's own instructions, a pattern or a video of the making, read with look when the steps or the sizes are on screen, never guessed from a title or a description; a kit is listed from what the instructions name, each material by its working property, never by a brand alone; where to get a thing comes from search where=shops or a maker's own page, with a link; a substitute is named only where a source gives the property it must match; where to learn is a place a person can write to, from its own page.",
                    null, "the question asks how to make or do something", true),
            new Spec("choose",
                    "Choosing something to read, watch, listen to or use: books, videos and channels, podcasts, software, by fit to the ask or to the person's own shelf, never by charts, subscribers or stars; and finding again a video the person remembers but cannot name. Examples: \"a podcast about the history of the Tokyo subway with episodes under an hour\"; \"find the sewing video I saw: a silent canvas tote bag tutorial with the measurements on screen at the start\".",
                    List.of("candidates", "sift", "look"),
                    "The candidates that fit, each with why it fits and a link to it: a book by its catalogue entry, a video by its channel and moment, a show by its feed and episode, a project by its repository; what was looked at and set aside. A video the person remembers: the answer names as the one the candidate with the most remembered things found by looking, says it matches every one only when every one was found, and says in its first sentences which thing was not found or not looked at, for which candidates, with the candidates that remain. When more than one candidate has every remembered thing found, the answer names each of them as matching, best looked-at first, and says that what the person remembers fits more than one video, so they can say which they watched. A book: its catalogue record read with book_search kind=work, what it covers in the ask's terms, who it is for and where it was found, the heading, the list or the review, with a link to its record; a show: its feed read with podcast_search kind=episodes, the episodes' dates and lengths as the feed gives them, a transcript read where what is said matters, with the feed address and a link to the episode. For books and shows the answer says which records and feeds were read and which candidates stand on a directory entry alone, names first what the person asked for that was not checked, and never calls a candidate a fit on a chart, a rating or a count.",
                    "A video is judged by looking at it with look when the ask names what one sees or hears, never from its title or description alone. A video the person remembers but cannot name begins with candidates, called once per set of languages with the name of the thing made or shown in two to four words in each language the video may be in, what the person remembers of the video itself as short phrases in their own words in remembered, the dates and the length when the person gave them, and the language of the title or the on-screen text when they said it, never a search: it enumerates for itself (deep searches of its own plain phrasings, the pages where other people describe a video in the words remembered, whole channels, the Data API in date order, the other indexes, the person's own watch history), filters by what a machine can check and hands back the pool best fit first under a key; the remembered features (the material, a colour, that nobody speaks, a pocket) go in remembered as the person said them and are never typed into things, since a title seldom names them. Then sift, given that key in from and the remembered things in what, judges every candidate's thumbnail and the first storyboard sheet of those that pass, and hands back the survivors best first; when it says some were not reached, sift again with page=2 while the budget allows. Then the first five survivors are looked at together in one look's videos, sparse, for every remembered thing at once; then the next five, down the survivors while the budget lasts, before any one of them is looked at again; the best gets a full look; and the answer names the video, the second each remembered thing was seen at, and how each was checked. A book comes from a catalogue, a show from its feed, a project from its repository, each by fit. A book's pool is enumerated: every row the catalogue holds under a subject heading, book_search kind=subject paged through with page until it gives no more, and the titles on the reading lists, syllabi and further-reading sections the web search finds, each then looked up in the catalogue; never the catalogue's relevance order, its ratings or its edition counts as the pool or the order, since those count editions, which is popularity; a book is judged by its record read with kind=work and by what a list or a review says it covers. A show's pool is every row the directory holds for the words, the language and the category, podcast_search kind=shows with the index's file first, never its trending list and never a browse by popularity; a show is judged by its own feed, kind=episodes, whose titles, dates and lengths a machine checks against the ask, and by a transcript where what is said matters, never by its chart position, its subscribers or the count of its reviews.",
                    null, "the question asks which book, video, channel, show or project to pick", true),
            new Spec("generic",
                    "Answer the question: research with every source and the report's basis, when no other goal applies. Example: \"why did the Saros cycle matter to Babylonian astronomers\".",
                    List.of(),
                    "The basis alone: the answer with its claims, each cited, the disputes, the checks, the sources, a link on the line of every named thing.",
                    "What is found is checked against its source's own text; a thing that can be looked at, a video, a page, a record, is looked at rather than described from a snippet.",
                    null, "no other goal's words apply", true));

    /** A goal backed by its fields: the rules are theirs, the rest is the spec's. */
    static final class ProfileGoal implements Goal {
        private final Spec spec; private final List<Profile> profiles;
        ProfileGoal(Spec spec, List<Profile> profiles) { this.spec = spec; this.profiles = List.copyOf(profiles); }
        @Override public String name() { return spec.name(); }
        @Override public String words() { return spec.words(); }
        @Override public List<String> instruments() { return spec.instruments(); }
        @Override public String shape() { return spec.shape(); }
        @Override public String decisionRules() { return spec.decisionRules(); }
        @Override public List<Profile> profiles() { return profiles; }
        @Override public boolean joinsByWords() { return spec.joinsByWords(); }
        @Override public String planRules() { return rules(Profile::planRules); }
        @Override public String criticRules() { return rules(Profile::criticRules); }
        @Override public String writerRules() { return rules(Profile::writerRules); }
        private String rules(Function<Profile, String> kind) {
            StringBuilder b = new StringBuilder();
            for (Profile p : profiles) { String r = kind.apply(p); if (r != null && !r.isBlank()) b.append(b.isEmpty() ? "" : "\n\n").append(r.strip()); }
            return b.toString();
        }
        @Override public Applies appliesTo(String question) {
            String q = question == null ? "" : question;
            if (!spec.joinsByWords()) {
                for (Profile p : profiles) if (p.suggests(q)) return Applies.no("the question looks like " + p.name() + "'s, and " + spec.why());
                return Applies.no(spec.why());
            }
            if (spec.applies() != null && spec.applies().matcher(Vocabulary.norm(q)).find()) return Applies.yes(spec.why());
            for (Profile p : profiles) if (p.applies(q)) return Applies.yes("the question's words are " + p.name() + "'s: " + spec.why());
            return Applies.no("nothing in the question says " + spec.why());
        }
    }

    private static volatile List<Goal> known;

    /** One of each goal this build knows, in the specs' order; the generic goal last. */
    public static List<Goal> known() {
        List<Goal> k = known;
        if (k == null) {
            Map<String, List<Profile>> backing = new LinkedHashMap<>();
            for (Profile p : Profiles.known()) if (!p.goalName().isBlank()) backing.computeIfAbsent(p.goalName(), x -> new ArrayList<>()).add(p);
            List<Goal> out = new ArrayList<>();
            for (Spec s : SPECS) out.add(new ProfileGoal(s, backing.getOrDefault(s.name(), List.of())));
            known = k = List.copyOf(out);
        }
        return k;
    }

    /** The generic goal: the basis alone. */
    public static Goal generic() { return named("generic"); }

    public static Goal named(String name) {
        for (Goal g : known()) if (g.name().equalsIgnoreCase(name == null ? "" : name.strip())) return g;
        return null;
    }

    /** The goals a question's words apply to, each with its reason; the generic goal alone when none does. For the brain to weigh. */
    public static List<Goal> forQuestion(String question) {
        List<Goal> out = new ArrayList<>();
        for (Goal g : known()) if (!g.name().equals("generic") && g.appliesTo(question).yes()) out.add(g);
        if (out.isEmpty()) out.add(generic());
        return out;
    }

    /** The reasons, applying or not, for every goal: what the brain reads beside the question. */
    public static Map<String, Goal.Applies> reasons(String question) {
        Map<String, Goal.Applies> out = new LinkedHashMap<>();
        for (Goal g : known()) out.put(g.name(), g.appliesTo(question));
        return out;
    }

    /** The goals whose backing fields are enabled on this library, and the generic goal. */
    public static List<Goal> enabled(LibraryStore store) {
        List<Goal> out = new ArrayList<>();
        Set<String> on;
        try { on = store == null ? null : Profiles.enabled(store); } catch (IOException e) { on = null; }
        for (Goal g : known()) {
            if (g.profiles().isEmpty()) { out.add(g); continue; }
            boolean any = false;
            for (Profile p : g.profiles()) if (on == null || on.contains(p.name().toLowerCase(Locale.ROOT))) any = true;
            if (any) out.add(g);
        }
        return out;
    }

    /** The goal a field backs, or null. */
    static Goal of(Profile p) {
        if (p == null || p.goalName().isBlank()) return null;
        return named(p.goalName());
    }
}
