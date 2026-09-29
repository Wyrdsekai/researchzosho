package org.researchzosho.librarian.profiles;

import org.researchzosho.librarian.Evidence;
import org.researchzosho.librarian.Fields;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import org.researchzosho.librarian.Patrons;
import org.researchzosho.librarian.FamilyPages;
import org.researchzosho.librarian.Profiles;
import org.researchzosho.librarian.FamilyDate;
import org.researchzosho.librarian.Interaction;
import org.researchzosho.librarian.FamilyPeople;
import java.util.concurrent.ConcurrentHashMap;
import org.researchzosho.librarian.Gedcom;
import org.researchzosho.librarian.LibraryStore;
import org.researchzosho.librarian.Mentions;
import org.researchzosho.librarian.Profile;
import org.researchzosho.librarian.Vocabulary;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Collection;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Console;
import org.researchzosho.librarian.FamilyWho;
import org.researchzosho.librarian.KanjiForms;
import org.researchzosho.librarian.Looked;
import org.researchzosho.librarian.SearchLog;
import org.researchzosho.librarian.FamilyIdentity;
import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.researchzosho.Config;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.drive.Judge;
import org.researchzosho.librarian.Acquisitions;
import org.researchzosho.librarian.Corpus;
import org.researchzosho.librarian.Crews;
import org.researchzosho.librarian.Council;
import org.researchzosho.librarian.FamilyAccount;
import org.researchzosho.librarian.FamilyDecisions;
import org.researchzosho.librarian.FamilyDoubts;
import org.researchzosho.librarian.FamilyChecks;
import org.researchzosho.librarian.FamilyFolder;
import org.researchzosho.librarian.FamilyLife;
import org.researchzosho.librarian.Finding;
import org.researchzosho.librarian.FamilySame;
import org.researchzosho.librarian.FamilyKin;
import org.researchzosho.librarian.FamilyLinks;
import org.researchzosho.librarian.FamilyAsking;
import org.researchzosho.librarian.FamilyMentions;
import org.researchzosho.librarian.FamilyNameHistory;
import org.researchzosho.librarian.FamilyNamePages;
import org.researchzosho.librarian.FamilySummary;
import org.researchzosho.librarian.FamilyNameQuestions;
import org.researchzosho.librarian.FamilyNames;
import org.researchzosho.librarian.FamilyQuestions;
import org.researchzosho.librarian.FamilyReads;
import org.researchzosho.librarian.FamilyReset;
import org.researchzosho.librarian.FamilySplit;
import org.researchzosho.librarian.FamilyTranscript;
import org.researchzosho.librarian.FamilyTree;
import org.researchzosho.librarian.Frontier;
import org.researchzosho.librarian.GeniFamily;
import org.researchzosho.librarian.Graph;
import org.researchzosho.librarian.Jobs;
import org.researchzosho.librarian.LibraryProtocol;
import org.researchzosho.librarian.RawCapture;
import org.researchzosho.records.RecordSources;
import org.researchzosho.tools.ContentPolicy;
import org.researchzosho.tools.DocText;
import org.researchzosho.tools.Fetch;
import org.researchzosho.tools.ImageText;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.time.LocalDate;
import org.researchzosho.drive.DeclineJudge;
import org.researchzosho.drive.Declined;
import org.researchzosho.librarian.Declines;
/**
 * The genealogy profile: kinship predicates, GEDCOM in and out, and the living-person rule. Written
 * against the core's graph the way any field's profile is — persons, places and events are core
 * node kinds; what this profile adds is the vocabulary of relations a family tree asserts, an
 * importer that turns a GEDCOM file into nodes and draft findings with the file as their source,
 * an exporter that hands the person subgraph back as GEDCOM, and the living rule, which decides what the research asks of a
 * person: of somebody who may be living, their work and public life, not their death records.
 *
 * <p>A module: on by default, which means available, and it acts only on genealogy work somebody asked for (its own commands and pages,
 * {@code research ask … --genealogy}, the web page's box, {@code field: "genealogy"}, a yes to the chat's question) and on what that
 * work filed. A question that looks like family history is told that genealogy mode is there, and runs as ordinary research.
 */
public final class GenealogyProfile implements Profile {

    /**
     * The libraries (by folder) and the questions the core asked genealogy to read or decide something about ({@link #onGraph},
     * {@link #onDecision}, {@link #yearsOf}): a library that holds no genealogy work, and a run that is not genealogy's, are never asked.
     * Null, and nothing kept, unless a test that holds the module to its own work sets it.
     */
    public static volatile Set<String> ASKED;

    private static void noteAsked(String what) { Set<String> s = ASKED; if (s != null) s.add(what); }

    @Override public String name() { return "genealogy"; }

    @Override public String description() { return "kinship relations, GEDCOM import and export"; }

    @Override public List<Vocabulary.Term> predicates() {
        return List.of(
                new Vocabulary.Term("parent-of", "is a parent of", List.of("father of", "mother of", "parent", "has son", "has daughter", "has child"), ""),
                new Vocabulary.Term("child-of", "is a child of", List.of("son of", "daughter of", "child"), ""),
                new Vocabulary.Term("married-to", "was married to", List.of("spouse of", "husband of", "wife of", "married", "spouse", "入夫"), ""),
                new Vocabulary.Term("step-parent-of", "is a step-parent (the husband or wife of a parent, not a parent by birth) of", List.of("stepfather of", "stepmother of", "step-parent of", "step father of", "step mother of", "継父", "継母", "継親"), ""),
                new Vocabulary.Term("foster-child-of", "was brought up as a foster child by", List.of("foster child of", "foster son of", "foster daughter of", "fostered by", "里子"), ""),
                new Vocabulary.Term("sex", "is recorded as (male or female)", List.of("gender"), ""),
                new Vocabulary.Term("adopted-by", "was adopted by", List.of("adopted son of", "adopted daughter of", "adopted child of", "adopted into", "養子", "婿養子", "養女"), ""),
                new Vocabulary.Term("sibling-of", "is a brother or sister of", List.of("brother of", "sister of", "sibling", "elder brother of", "younger brother of", "elder sister of", "younger sister of", "兄", "弟", "姉", "妹"), ""),
                new Vocabulary.Term("relative-of", "is a relative, other than a parent, child, spouse, brother or sister (a grandchild, an uncle, a cousin, a niece: the quote says which), of",
                        List.of("grandchild of", "grandson of", "granddaughter of", "grandparent of", "grandfather of", "grandmother of", "great grandchild of", "great grandparent of", "uncle of", "aunt of",
                                "nephew of", "niece of", "cousin of", "in law of", "relative", "孫", "曾孫", "玄孫", "甥", "姪", "いとこ", "叔父", "伯父", "叔母", "伯母"), ""),
                new Vocabulary.Term("head-of-household", "headed the household of", List.of("household head of", "head of the family of", "戸主"), ""),
                new Vocabulary.Term("born-on", "was born on the date", List.of("date of birth", "birth date", "生年月日"), ""),
                new Vocabulary.Term("died-on", "died on the date", List.of("date of death", "death date", "没年月日"), ""),
                new Vocabulary.Term("born-in", "was born in", List.of("birthplace", "born at", "born"), ""),
                new Vocabulary.Term("died-in", "died in", List.of("place of death", "died at", "died"), ""),
                new Vocabulary.Term("lived-in", "resided in", List.of("resided in", "residence", "lived at", "lives in", "living in", "resides in", "residing in", "lives at"), ""),
                new Vocabulary.Term("migrated-to", "emigrated or immigrated to", List.of("emigrated to", "immigrated to", "moved to", "arrived in"), ""),
                new Vocabulary.Term("buried-in", "was buried in", List.of("burial", "interred in"), ""),
                new Vocabulary.Term("baptised-in", "was baptised or christened in", List.of("baptized in", "christened in", "christened at", "baptised at", "baptism", "christening"), ""),
                new Vocabulary.Term("baptised-on", "was baptised or christened on the date", List.of("baptized on", "christened on", "date of baptism", "date of christening"), ""),
                new Vocabulary.Term("occupation", "worked as", List.of("worked as", "profession", "was a", "works as", "working as", "employed as"), ""),
                new Vocabulary.Term("informant-for", "gave the details for a record about (reported a birth or a death to the registrar)", List.of("informant", "informant of", "reported the death of", "reported the birth of", "届出人"), ""),
                new Vocabulary.Term("witness-for", "was a witness at an event (a marriage, a baptism, a will) of", List.of("witness", "witness at", "witness to", "witness of", "証人"), ""),
                new Vocabulary.Term("godparent-of", "was a godparent at the baptism of", List.of("godfather of", "godmother of", "godparent", "sponsor of", "代父", "代母"), ""),
                new Vocabulary.Term("aged", "was, at the date of the record, of the age written as", List.of("age", "age at death", "age at the event", "享年", "行年", "年齢"), ""),
                new Vocabulary.Term("life-event", "had this happen in their life (an office or a rank, an award, a deed, a work published, a patent, a company founded, a school finished, military service, a journey, "
                        + "an audience or a meeting, a trial: a few of the account's own words say what), which was",
                        List.of("event", "held office", "awarded", "appointed", "founded", "published", "served in", "graduated from", "took part in", "known for", "accomplishment"), ""),
                // names over a life and families as things (0.5.0): a name is a claim with a kind and a period, a family a node with members
                new Vocabulary.Term("has-name", "carried the name (at birth, by marriage, adoption, succession, a legal change, or as a pen, religious or other name)",
                        List.of("birth name", "born as", "née", "maiden name", "married name", "later name", "former name", "took the name", "took the family name", "changed name to", "also known as",
                                "known as", "pen name", "art name", "posthumous name", "religious name", "旧姓", "本名", "通称", "別名", "号", "雅号", "筆名", "戒名", "法名", "改名"), ""),
                new Vocabulary.Term("member-of", "was a member of the family (a house, a line, a clan, a 家)", List.of("member of", "member of the family", "entered the family", "joined the family", "belonged to the family", "of the house of", "入籍", "家に入る"), ""),
                new Vocabulary.Term("parent-in-law-of", "is a parent-in-law (the father or mother of the husband or wife) of", List.of("father in law of", "mother in law of", "parent in law of", "義父", "義母", "舅", "姑"), ""),
                new Vocabulary.Term("heir-of", "was the heir, or succeeded as head of the family, of", List.of("heir of", "heir to", "succeeded", "succeeded to", "家督相続", "跡を継いだ", "跡継ぎ"), ""),
                new Vocabulary.Term("branch-of", "is a branch (cadet line, 分家) of the family", List.of("branch of", "cadet branch of", "分家"), ""),
                new Vocabulary.Term("family-seat", "had its seat (本籍, an estate, a clan's place of origin) in", List.of("seat", "family seat", "本籍", "本貫", "본관"), ""),
                new Vocabulary.Term("founded-by", "was founded by", List.of("founder", "founded by", "初代", "始祖"), ""));
    }

    @Override public List<String> nodeKinds() { return List.of("family"); }

    @Override public String register() {
        return "Work from records. For each fact about a person, note WHAT record says it (a register entry, a census line, a deed, "
                + "a newspaper issue, a directory, a patent), WHO holds it (the archive, office or collection) and WHERE in it (volume, roll, "
                + "page, entry, image), and say whether you read the original, an image of it, a transcript, an index or someone's tree. "
                + "Say who gave the information and how close they stood to the event: a death record's birth date is second-hand. "
                + "A name alone fits many people. Tie a record to the person with a second identifier — a spouse, a parent, a child, an "
                + "occupation, an address, an age — and say in the note which one agreed; with none, note the record as a candidate. "
                + "Write names as the source writes them, in its own script, with a reading only when a source gives it (a reading you work out yourself is a guess at a name), and dates as "
                + "written (an era year, 'about', 'before') beside the converted year. An index, a compiled tree or a family-history "
                + "site points to a record: follow it to the record and cite the record. Copies of one original count once. When a search "
                + "of a named collection finds nothing, note that as well — the collection, the name forms and the years searched. "
                + "A public event the person had a part in is a fact of their life like a birth: a crisis during their time in an office, a reprimand, a founding, "
                + "a trial, a disaster, an honour. Note it WHEN YOU READ IT, with the page that tells it, its date and the person's part in it. "
                + "The report is written from the notes: what you only mention when you finish, and did not note, does not reach it. "
                + "Of a living person note only what public sources say of their work and public life.";
    }

    /**
     * Words that mark a family-history question. Only words that mean a FAMILY: "ancestor", "lineage", "descendant", "pedigree", 祖先 and
     * their like are everyday words of biology, version control and data engineering (a common ancestor, a cell lineage, a descendant
     * node), and a library for every field must not read those as genealogy. The fallback only: a question that names someone the
     * owner's own family accounts are about is recognised without any of them.
     */
    private static final List<String> WORDS = List.of(
            "genealog", "généalog", "family tree", "family history", "my ancestor", "our ancestor", "my forebear",
            "great-grandfather", "great-grandmother", "great-grandparent", "my grandfather", "my grandmother", "my grandparents",
            "先祖", "家系図", "戸籍", "系図", "曾祖父", "曾祖母", "曽祖父", "曽祖母", "高祖父", "高祖母", "族谱", "族譜", "家谱", "家譜", "족보",
            "ahnenforschung", "familienforschung", "familienstammbaum", "arbre généalogique", "mis antepasados", "stamboomonderzoek",
            "släktforsk", "slektsforsk", "slægtsforsk", "sukututki", "родослов");

    @Override public boolean applies(String question) {
        String q = Vocabulary.norm(question);
        for (String w : WORDS) if (q.contains(w)) return true;
        return false;
    }

    /** The terminal a person types into, or null: a command in a script or a pipe asks nothing and never waits (Java 22 gives a console there too). */
    private static Console console() { return Interaction.interactive() ? System.console() : null; }

    /** Genealogy is a module: it joins a run only when somebody asks for it, and its words only suggest it. */
    @Override public boolean joinsOnlyWhenAsked() { return true; }

    /** A family-history question: its words, or the name of somebody the family's own texts and tree files are about. */
    @Override public boolean suggests(LibraryStore store, String question) {
        if (applies(question)) return true;
        if (store == null || !Fields.holdsWork(store, this)) return false;
        String q = Vocabulary.norm(KanjiForms.modern(question));   // names are compared in today's character forms, whichever way each side wrote them
        try { return namesFamily(q, ownNames(store)); } catch (IOException ignored) { }
        return false;
    }

    /**
     * Whether a question (folded) names somebody the family's own work is about. A name counts as whole words, never inside a longer word:
     * Frank is not in Frankfurt, nor Allen in fallen. A name of one word (a first name alone, as a family account often writes it), or a
     * name in kanji or kana of two characters, counts only beside another name of the family: many questions hold one first name.
     */
    static boolean namesFamily(String q, Set<String> names) {
        int single = 0;
        for (String name : names) {
            boolean cjk = name.codePoints().anyMatch(c -> c >= 0x2E80);
            boolean found = cjk ? q.contains(name) : Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(name) + "(?![\\p{L}\\p{N}])").matcher(q).find();
            if (!found) continue;
            if (cjk ? name.codePointCount(0, name.length()) >= 3 : name.strip().contains(" ")) return true;
            if (++single >= 2) return true;
        }
        return false;
    }

    @Override public String offer() {
        return "This looks like family history. Genealogy mode searches record collections (registers, newspapers, censuses), builds your family tree and uses what the library already knows about your relatives.";
    }

    @Override public String choice() { return "Family history (genealogy mode): record collections, your family tree and your relatives' facts"; }

    @Override public Path workFolder(LibraryStore store) { return store.root().resolve("family"); }

    @Override public void onGraph(Graph.Reading g) {
        noteAsked(g.store().root().toString());
        FamilyPeople.read(g, ownWriters());
        // the claims the dates or the words set aside, in the linked view only: the link pass reads every claim as it was filed
        if (linked) FamilyDoubts.read(g);
    }

    /**
     * A family name or a given name alone that a person carries as another name leads no claim of the family's work to that person: strangers
     * share it ({@link FamilyNames#oneWordOtherName}). The claim keeps it as a name of its own, somebody the family is asked about.
     */
    @Override public boolean readsOtherName(String written, Vocabulary.Term entry) { return !FamilyNames.oneWordOtherName(written, entry); }

    /** Whether this view reads the claims with the links worked out from the evidence ({@link FamilyLinks}); the link pass itself reads them without. */
    private final boolean linked;

    public GenealogyProfile() { this(true); }

    private GenealogyProfile(boolean linked) { this.linked = linked; }

    /** The model the link pass asks for readings and choices, set by a test; null for the one the library is set up with. */
    private static volatile FamilyLinks.Model linkModel = null;

    public static void useLinkModel(FamilyLinks.Model m) { linkModel = m; }

    /** The model the link pass asks: a test's, or the library's own model server. */
    static FamilyLinks.Model linkModel() { FamilyLinks.Model m = linkModel; return m != null ? m : FamilyLinks.liveModel(); }

    /** Genealogy's view without its links: what the link pass works them out from. */
    public static GenealogyProfile unlinked() { return new GenealogyProfile(false); }

    /** In genealogy's own view, each name of a claim is the person the evidence links it to ({@link FamilyLinks}). */
    @Override public Graph.Links links(LibraryStore store, List<Finding> claims) throws IOException { return linked ? FamilyLinks.links(store, claims) : null; }

    /** An open question an older version filed for the family, before questions carried their field: its bracket says where it came from. */
    @Override public boolean ownsLine(String kind) {
        return kind != null && (kind.contains(FamilyReset.FROM_TREE) || kind.contains(FamilyDecisions.RECORD) || kind.contains("(from a family file)"));
    }

    /** The words for a relation between people or a fact of a person's life, for a model to copy: the family relations the graph reads. */
    public static final String PERSON_RELATIONS = "When the relation is between two people, or is a fact of one person's life, write the predicate as one of these words, copied exactly: "
            + "parent-of, child-of, married-to, sibling-of, relative-of, born-on (the object is a date), born-in (a place), died-on, died-in, buried-in, lived-in, occupation, migrated-to, "
            + "has-name (the object is a name the person carried: a birth name, a married or adopted name, a name taken as heir, a pen name), member-of (the object is a family: the Morita family), "
            + "parent-in-law-of, heir-of.";

    @Override public String extractionRule() { return PERSON_RELATIONS; }

    /** What the chat is told of the family's tool, in a library that holds family work. */
    @Override public String chatNote() {
        return "Family history, \"who is who\", \"which one is my uncle\", \"go through the people who wait\": library_who. A name is shared by many people and you cannot know which one is the person's relative; they can. op=show gives one relative's entries: put every entry to the person with its number, who it is, what its pages say that the family also says, and one or two of its pages, then ask which is their relative. Record exactly what they say with op=answer (two numbers when they say two entries are the same person; none=true; later=true when they cannot tell), then go on to the next with op=show until they stop or nobody waits. When they tell you something about the relative (their work, a place, a school), keep it with op=tell in their words. What goes into op=answer is the person's own word, and only that: an entry that seems to fit is put to them as a question. "
                + "The questions about names and families (who a relative written only by a family name is, whether two entries are one person, how and when a name changed, a birth or an adoptive parent) come from library_who with kind=names: op=show gives one with its code and its options, each saying what it does; read it to the person as it is, with every option, and record the key they choose with op=answer, the code and choice, and year for a question of when.\n";
    }

    @Override public List<String> chatTools() { return List.of("library_who"); }

    /** For a family-history run about a person in the family: the name forms, years and record collections no search has tried yet. */
    @Override public String knownBlock(LibraryStore store, String question, List<SearchLog.Entry> searched) {
        try { return FamilyQuestions.neverSearched(store, question, searched); } catch (IOException | RuntimeException e) { return ""; }
    }

    /**
     * Any library, genealogy on or off: the relations {@code profile enable genealogy} wrote into predicates.md go back out, since they
     * are read with genealogy's own work now (a relation the owner wrote or changed stays).
     */
    @Override public List<String> tidy(LibraryStore store) throws IOException {
        String taken = forgetWrittenRelations(store);
        return taken == null ? List.of() : List.of(taken);
    }

    /**
     * A library that holds family work from before runs were recorded: its own intake's claims, or the runs the upgrade found by where they
     * came from (the research log said genealogy's rules joined them, genealogy research filed them, or they researched a question it
     * filed). The family folder is made, and the people nodes.md marks a person although nothing of the family names them are listed for
     * the owner, who decides.
     */
    @Override public List<String> upgrade(LibraryStore store, Set<String> recorded) throws IOException {
        List<String> notes = new ArrayList<>();
        List<Finding> all = store.scanFindings().findings();
        Set<String> own = new HashSet<>();
        for (Finding f : all) if (ownWriters().contains(f.writer())) own.add(f.id());
        if (own.isEmpty() && recorded.isEmpty()) return notes;
        Files.createDirectories(workFolder(store));
        // who nodes.md marks a person although no family text, tree file or family-history run names them: perhaps a kind an older version wrote
        Set<String> familyNodes = new HashSet<>();
        Graph g = Graph.build(store);
        for (Finding f : all) {
            if (f.triple() == null) continue;
            boolean family = own.contains(f.id()) || f.sources().stream().anyMatch(src -> src.whyItMatters() != null && recorded.stream().anyMatch(id -> src.whyItMatters().startsWith("cited by " + id)));
            if (family) { familyNodes.add(g.nodeIdOf(f.triple().subject())); familyNodes.add(g.nodeIdOf(f.triple().object())); }
        }
        List<String> alone = new ArrayList<>();
        for (Vocabulary.Term t : g.curated().terms().values())
            if ("person".equals(Graph.kindOf(t.description())) && !familyNodes.contains(t.slug())) alone.add(t.description().contains(":") ? t.description().substring(t.description().indexOf(':') + 1).strip() : t.slug());
        if (!alone.isEmpty()) notes.add("nodes.md marks " + alone.size() + (alone.size() == 1 ? " name" : " names") + " a person although no family text, tree file or family-history run names them. An older version may have written that for a person it only worked out: "
                + String.join(", ", alone.subList(0, Math.min(20, alone.size()))) + (alone.size() > 20 ? " and " + (alone.size() - 20) + " more" : "") + ". Nothing was changed. To make one an ordinary name again: researchzosho graph kind \"<name>\" concept");
        // what an older library holds that only the family can settle now that names have kinds and periods: nothing is rewritten, it is asked
        String names = namesNoteOnce(store);
        if (names != null) notes.add(names);
        return notes;
    }

    /** Where a library notes that it told its owner once of the questions about names and families it held when names came in. */
    static Path namesToldFile(LibraryStore store) { return store.root().resolve("catalog").resolve("migrations").resolve("names-questions.txt"); }

    /**
     * Once per library: the sentence that says how many questions about names and families wait, and the command that asks them; null when
     * it was said before, or none waits. The upgrade says it; a library an earlier build already upgraded, before these questions existed,
     * hears it from the first genealogy command it runs ({@link #cli}).
     */
    static String namesNoteOnce(LibraryStore store) throws IOException {
        Path f = namesToldFile(store);
        if (Files.exists(f)) return null;
        List<FamilyNameQuestions.Question> names = FamilyNameQuestions.open(store);
        String note = names.isEmpty() ? null : (names.size() == 1 ? "One question" : names.size() + " questions") + " about names and families " + (names.size() == 1 ? "waits" : "wait") + " for the family: "
                + FamilyNameQuestions.counted(names) + ". Nothing was changed. To answer them one at a time, each answer saying what it does: researchzosho genealogy who";
        Files.createDirectories(f.getParent());
        Files.writeString(f, LocalDate.now() + "\t" + (note == null ? "no question about names and families waited" : note) + "\n", StandardCharsets.UTF_8);
        return note;
    }

    /** A library an earlier build upgraded: the field ledger's marker is there, written before the questions about names existed. */
    private static boolean upgradedBefore(LibraryStore store) { return Files.exists(store.root().resolve("catalog").resolve("migrations").resolve("run-fields.txt")); }

    /**
     * How genealogy research wrote each person's question in the builds before jobs carried their field: the person's name, what is known
     * of them in brackets, a colon, then one of these. The command filed them as depth runs and put the rest on the open questions, which
     * the Questions page then sent as broad runs. The command wrote them; a person's own question does not.
     */
    static final List<String> OLD_TEMPLATES = List.of(
            ": answer each of these questions on its own, and say for each whether it is answered, not found, or in conflict between records.",
            ": what records exist for this person, and what did they do in life",
            ": what do public sources say of this person's work and public life?");

    @Override public boolean filedByOwnCommand(JsonNode args) {
        if (args == null) return false;
        String q = args.path("question").asText("");
        for (String t : OLD_TEMPLATES) if (q.contains(t)) return true;
        return false;
    }

    /**
     * The relations each earlier version's {@code profile enable genealogy} wrote into predicates.md, by name, oldest first: 0.4.6 wrote
     * the first nine. Every one of them wrote today's description of a relation and some of today's other wordings; today's own list is
     * {@link #predicates()}.
     */
    static final List<List<String>> WRITTEN_BEFORE = List.of(
            List.of("parent-of", "child-of", "married-to", "born-in", "died-in", "lived-in", "migrated-to", "buried-in", "occupation"),
            List.of("parent-of", "child-of", "married-to", "adopted-by", "head-of-household", "born-in", "died-in", "lived-in", "migrated-to", "buried-in", "occupation"),
            List.of("parent-of", "child-of", "married-to", "adopted-by", "head-of-household", "born-on", "died-on", "born-in", "died-in", "lived-in", "migrated-to", "buried-in", "occupation"),
            List.of("parent-of", "child-of", "married-to", "adopted-by", "sibling-of", "relative-of", "head-of-household", "born-on", "died-on", "born-in", "died-in", "lived-in", "migrated-to", "buried-in", "occupation"),
            List.of("parent-of", "child-of", "married-to", "adopted-by", "sibling-of", "relative-of", "head-of-household", "born-on", "died-on", "born-in", "died-in", "lived-in", "migrated-to", "buried-in", "occupation", "life-event"),
            List.of("parent-of", "child-of", "married-to", "adopted-by", "sibling-of", "relative-of", "head-of-household", "born-on", "died-on", "born-in", "died-in", "lived-in", "migrated-to", "buried-in", "baptised-in", "baptised-on", "occupation", "life-event"),
            List.of("parent-of", "child-of", "married-to", "adopted-by", "sibling-of", "relative-of", "head-of-household", "born-on", "died-on", "born-in", "died-in", "lived-in", "migrated-to", "buried-in", "baptised-in", "baptised-on", "occupation", "informant-for", "witness-for", "godparent-of", "life-event"),
            List.of("parent-of", "child-of", "married-to", "adopted-by", "sibling-of", "relative-of", "head-of-household", "born-on", "died-on", "born-in", "died-in", "lived-in", "migrated-to", "buried-in", "baptised-in", "baptised-on", "occupation", "informant-for", "witness-for", "godparent-of", "aged", "life-event"));

    /**
     * The relations {@code profile enable genealogy} wrote into predicates.md, taken out as a block: those with genealogy's description
     * and none but its wordings, when they are most of what one version wrote (four in five of it). A relation the owner wrote or changed
     * stays. The sentence for the owner, or null when nothing was taken out.
     */
    String forgetWrittenRelations(LibraryStore store) throws IOException {
        Vocabulary declared = Graph.declaredPredicates(store);
        Set<String> seeded = new LinkedHashSet<>();
        for (Vocabulary.Term t : predicates()) {
            Vocabulary.Term have = declared.get(t.slug());
            if (have == null || !Vocabulary.norm(have.description()).equals(Vocabulary.norm(t.description()))) continue;
            Set<String> mine = new HashSet<>(); for (String a : t.also()) mine.add(Vocabulary.norm(a));
            if (have.also().stream().allMatch(a -> mine.contains(Vocabulary.norm(a)))) seeded.add(t.slug());
        }
        List<List<String>> versions = new ArrayList<>(WRITTEN_BEFORE);
        versions.add(predicates().stream().map(Vocabulary.Term::slug).toList());
        boolean block = versions.stream().anyMatch(v -> v.stream().filter(seeded::contains).count() * 5 >= v.size() * 4L);
        if (!block || Graph.forgetPredicates(store, seeded) == 0) return null;
        return "The family relations that turning genealogy on once wrote into catalog/graph/predicates.md were taken out: " + seeded.size() + " of them. They are read with the family's own work now, and every other claim keeps its own words.";
    }

    /** The names of the people the family's own texts and tree files are about, folded for comparing; kept until a claim or a name changes. */
    private static final Map<Path, Object[]> NAMES = new ConcurrentHashMap<>();

    static Set<String> ownNames(LibraryStore store) throws IOException {
        String key = stamp(store.findingsDir()) + "|" + stamp(Graph.dir(store).resolve("nodes.md")) + "|" + stamp(Graph.dir(store).resolve("merges.tsv"));
        Object[] held = NAMES.get(store.root().toAbsolutePath());
        if (held != null && held[0].equals(key)) { @SuppressWarnings("unchecked") Set<String> names = (Set<String>) held[1]; return names; }
        Set<String> names = new LinkedHashSet<>();
        Graph g = FamilyPeople.view(store);
        Set<String> mine = new HashSet<>();
        for (Vocabulary.Term t : new GenealogyProfile().predicates()) mine.add(t.slug());
        Set<String> own = new HashSet<>();
        for (Finding f : store.scanFindings().findings()) if (new GenealogyProfile().ownWriters().contains(f.writer())) own.add(f.id());
        for (Graph.Edge e : g.edges()) {
            if (!mine.contains(e.predicate()) || !own.contains(e.findingId())) continue;
            for (String id : new String[]{e.from(), e.to()}) {
                Graph.Node n = g.node(id);
                if (n == null || !"person".equals(n.kind())) continue;
                List<String> calledBy = new ArrayList<>(n.aliases()); calledBy.add(n.label());   // a person is named by any of their names
                for (String name : calledBy) {
                    String label = Vocabulary.norm(KanjiForms.modern(name).replaceAll("\\s*[(（][^)）]*[)）]\\s*$", ""));   // "John Ellis (born 1851)" is asked about as John Ellis
                    if (label.length() >= (label.chars().allMatch(c -> c < 128) ? 5 : 2)) names.add(label);
                }
            }
        }
        NAMES.put(store.root().toAbsolutePath(), new Object[]{key, names});
        return names;
    }

    private static String stamp(Path p) {
        try { return Files.exists(p) ? String.valueOf(Files.getLastModifiedTime(p).toMillis()) : "-"; } catch (IOException e) { return "?"; }
    }

    /** A fact disputed or retired: the questions the family research wrote with it, the person's own and those that name them as a lead, go. */
    @Override public List<String> onDecision(LibraryStore store, Finding decided) throws IOException {
        noteAsked(store.root().toString());
        FamilyNameHistory.onDecision(store, decided);   // a name disputed or retired takes back the other names it gave
        return FamilyQuestions.writtenWith(store, decided);
    }

    @Override public int[] yearsOf(String question) { noteAsked(question); return FamilyDate.lived(question); }

    @Override public int writtenLater(Finding f) { return FamilyDate.writtenLater(f); }

    /** "A parent-of B" and "B child-of A" are one fact, and so are the two ways of a marriage or of a brother and sister. */
    @Override public String factKey(String from, String predicate, String to) {
        return switch (predicate) {
            case "child-of" -> to + "\t" + "parent-of" + "\t" + from;
            case "married-to", "sibling-of" -> from.compareTo(to) <= 0 ? from + "\t" + predicate + "\t" + to : to + "\t" + predicate + "\t" + from;
            default -> null;
        };
    }

    /** /family in the chat: tell it what you know, read an account, draw the tree, run the checks. The same verbs as `researchzosho genealogy`. */
    @Override public boolean chatCommand(LibraryStore store, String line) {
        if (!(line.equals("/family") || line.startsWith("/family "))) return false;
        String rest = line.substring(7).strip();
        String[] head = rest.split("\\s+", 2);
        String verb = head[0], tail = head.length > 1 ? head[1].strip() : "";
        if (verb.isEmpty() || !Set.of("tell", "read", "tree", "check", "split", "source", "different", "related").contains(verb) || (tail.isEmpty() && !verb.equals("check"))) {
            System.out.println("  /family tell <what you know, in sentences>\n  /family read <file or web address>      a relative's notes, a photo of a register, a page that tells a family\n"
                    + "  /family tree <a name>                    draws the tree into family-tree.svg; the pages show it at /tree\n  /family check                            what cannot be true in the family's claims\n"
                    + "  /family split \"<name>\" --as \"<name (born 1851)>\" --claims F-…,F-…\n  /family source <file name or web address>  every fact that rests on one source, and which have no other source\n"
                    + "  /family different \"<name>\" \"<other name>\" --because \"<why>\"   two people the check took for one: written down, and not asked about again\n"
                    + "  /family related \"<name>\" \"<other name>\"   how two people are related, from the parent and marriage claims");
            return true;
        }
        List<String> args = new ArrayList<>(List.of("librarian", "genealogy", verb));
        if (verb.equals("tell") || verb.equals("tree")) args.add(tail);   // the sentences, or the name, whole
        else if (!tail.isEmpty()) { var m = Pattern.compile("\"([^\"]*)\"|(\\S+)").matcher(tail); while (m.find()) args.add(m.group(1) != null ? m.group(1) : m.group(2)); }
        try {
            if (!Profiles.isEnabled(store, name())) { System.out.println("  the genealogy field is turned off on this library: researchzosho profile enable genealogy"); return true; }
            if (verb.equals("tell") || verb.equals("read")) System.out.println("  reading… (the model reads every part of it; a long account takes minutes)");
            new GenealogyProfile().cli(store, args.toArray(new String[0]));
        } catch (Exception e) { System.out.println("  " + why(e, "that did not work: ")); }
        return true;
    }

    @Override public String example() { return "genealogy import tree.ged"; }

    @Override public String chatCommandHelp() { return "/family (your family: tell, read, tree, check) · to research a question as family history, say \"in genealogy mode\" with it"; }

    @Override public List<String> pagePaths() { return FamilyPages.PATHS; }

    @Override public Page page(LibraryStore store, Patrons.Patron patron, String path, String method, Map<String, String> query, Map<String, String> form) throws IOException {
        return FamilyPages.page(store, patron, path, method, query, form);
    }

    @Override public List<Link> menu() { return FamilyPages.menu(); }

    @Override public String homeSection(LibraryStore store, boolean writer) throws IOException { return FamilyPages.homeSection(store, writer); }

    @Override public String inboxNote(LibraryStore store, boolean writer) throws IOException { return FamilyPages.inboxNote(store, writer); }

    @Override public ObjectNode tool(String name, LibraryProtocol protocol, JsonNode args) throws IOException {
        return name.equals("library_who") ? FamilyWho.tool(protocol, args) : null;
    }

    /** A claim whose words were checked against the model's reading of a picture that nobody has checked yet: said when it is accepted. */
    @Override public String onAccept(LibraryStore store, Finding f) throws IOException {
        FamilyTranscript.Transcript read = FamilyTranscript.of(store, f);
        if (read == null || read.accepted()) return "";
        return "Its words were checked only against the model's reading of the picture " + read.file() + ", which nobody has checked yet. To compare that reading with the picture: researchzosho genealogy transcript \"" + read.file() + "\"";
    }

    @Override public String checkedAgainst(Finding f) { return FamilyTranscript.checkedAgainst(f); }

    @Override public String hint(String situation) {
        return switch (situation) {
            case "taken-off" -> "The command researchzosho genealogy research writes the questions again from what the library holds now, and asks which of those people to research.";
            case "unmerge-nothing" -> "researchzosho genealogy tidy joins two people of the family whose name was written two ways.";
            case "apart" -> "researchzosho genealogy split does that for a person of the family.";
            default -> "";
        };
    }

    @Override public boolean wantsRecords() { return true; }

    @Override public List<String> ownWriters() { return List.of("family-account", "gedcom-import"); }

    /** A family's claims are organised by person and relation in the graph; subject headings add nothing to them. */
    @Override public boolean filesUnderSubjects() { return false; }

    @Override public String planRules() {
        return "This is a family-history question. Split it by person and by kind of record: for each person, the records that "
                + "establish who they were in their place and time (civil or family registers, census, church or temple registers, graves), "
                + "then the records that show what they did (newspapers, directories and who's-who volumes, patents, land, probate and court "
                + "records, military, school, company and government lists, emigration and passenger records), and the events of their life that others wrote about: "
                + "offices and ranks, honours, what they built, wrote, invented or founded, and the public events they had a part in. "
                + "A person's own biography often lists an office and its dates and says nothing of what happened in it. So when the question or the library shows that a person held an office, a command or a post, "
                + "give one sub-question to that office in those years: what happened there during their time, what part they had in it, and why their time in it ended. "
                + "A resignation, a dismissal or an office that ended after a few months is a lead to a public event: search for the office and the year, not only for the person's name. "
                + "A person is also found through their family. When the question names brothers and sisters, parents, a husband or wife or children, give one sub-question to them: the records of a sister name the same parents and the same home, "
                + "a parent's obituary or entry in a who's-who names the children, a husband's biography names the wife and her father. Search for those relatives by their own names, together with the family name and the place, "
                + "above all when the person's own name is a common one or the person led a private life. Begin with what the library itself already holds about these relatives. "
                + "A person may carry more than one name over a life: a name at birth, a name taken at marriage, on adoption or on entering a family, a later legal name. "
                + "When the question gives the names with their years, search each period under the name the person carried then, and search both names together for the record of the change, which names both. "
                + "A name has more than one written form, and each part of the web knows the person under one of them: search every written form the question gives (\"also written\"): as written, in modern characters, "
                + "the kana reading when it is known, each romaji spelling (ō, o, ou, oh, oo) and both name orders, the form in the person's own script in sources of that language and the romanised form in English ones. "
                + "The characters decide which family a record is about: a match in romaji alone is a clue to follow to the record. "
                + "For a person of the last sixty years most of what is public is on the open web and not in an archive: their own site and profiles, employers and companies, talks, papers, patents, code, interviews and press. "
                + "Give the open web its own sub-questions for such a person, one for each written form of the name, each with a word for what the person does or where, and follow the person's own pages to what they link to. Name the country, the place "
                + "and the years in each sub-question, and write it for the language those records are in. Give one sub-question to the "
                + "records only the family can request (a closed register, a certificate) and how they are requested.";
    }

    @Override public String criticRules() {
        return "For family history the evidence is enough when each numbered question in the brief has one of three outcomes with its source: answered by a record, not found (with where it was looked for), or in conflict between records (both sides noted). A question with none of these is the gap to name. Then: each person is tied to their records by more than a name; each event "
                + "(a birth, a marriage, a death, a move, an occupation) rests on a record rather than on a tree or an index; records that "
                + "disagree are both noted; and the record groups usual for that place and time have each been searched: a variety of kinds of record, not many searches of one kind. A gap worth "
                + "another search names a person, a record group, a place and the years. An office whose dates are known and whose events are not, or a resignation with no reason given, is such a gap: the search is for the office and the year. "
                + "When the person was not identified, or only strangers of the same name were found, the evidence is not enough until the relatives the question names have been searched for by their own names as well. When the question gives other written forms of the name and the searches used only one of them, the others are a gap worth another search, on the open web as well as in the collections. "
                + "When the person carried more than one name, a period searched under a name the person did not carry then, a period not searched under the name carried then, and a change of name no search carried both names of are each a gap. "
                + "A record written under the other name for its date is kept and noted as a question about when the name changed.";
    }

    @Override public String writerRules() {
        return "Open with a section 'Answers': the numbered questions from the brief, each in one of three ways and no other: the answer, with the record that gives it; not found, with where it was looked for; or the records disagree, with both sides. A fact that the family or a tree site gave and a record now confirms is written as confirmed, with the record; one that no record confirms stays a clue and is written as one. After the themes add three sections: 'Life' — for each person, the events of their life in order of date, one line each: the date as the source writes it, what happened "
                + "(an office, a rank, an honour, a work, a patent, a company, a journey, a part in a public event), and its citation. A life is more than a birth and a death: leave no dated event you found out of it. 'Identity' — which records were tied to which person and by what identifier, and the "
                + "candidates that could not be tied; 'Records to request' — what only the family or a visit can obtain, where and how. "
                + "The searches that found nothing are listed after your text by the library itself. Write a name in its own script, with a reading or a romanised form only where a source gave one. "
                + "Write each person under the name they carried at the date of each event, with the later name in brackets the first time: 遠藤健二 (later 森田健二). State each conclusion at the strength "
                + "its evidence gives: proved, probable or possible. Of a living person write only what public sources say of their public life.";
    }

    @Override public String usage() {
        return "genealogy read <file|url|folder> [--by \"who wrote it\"] [--family 髙橋,Takahashi] [--steps N] [--list] [--again] [--skip epub,pdf] [--only notes]\n"
             + "                                                               a family's own written account, a page or a book that tells a family: its people and relations become draft claims. --family keeps a long book to the names you give\n"
             + "              genealogy read --again [--list] [--skip epub]    with no folder: the folder the library read last, every file in it read once more\n"
             + "              genealogy tell \"<what you know>\" [--by you]    the same, from what you type\n"
             + "              genealogy transcript [<picture>] [--accept | --edit | --text | --from <file>]   the writing in a picture as the model read it; accept it or correct it, and the facts read from it are checked again\n"
             + "              genealogy tree <person> [--out tree.svg]         the family tree around a person, as a picture\n"
             + "              genealogy research [<person> [<person>…]] [--family [--up 6] [--down 3]] [--now N] [--queue] [--list] [--skip-living] [--again]   the people you name are researched now; with no name, or with --family for their relatives, the library lists whom it would research, the least known first, and you choose whom to start with\n"
             + "              genealogy log <person>                           the research log: every search made for a person, with the date, where, the exact words and what came back\n"
             + "              genealogy life <person> [--with <other>]           a person's life in order of date, with the holes in it; --with puts two lives side by side: every dated claim about them, and where it is from\n"
             + "              genealogy reset [--all | --from <file name>] [--yes]              takes out what was read from your folder, so you can read it again; what came from Geni and web pages stays. --all takes that out too and prints the commands that read it again. --from takes out one file. A copy is saved first\n"
             + "              genealogy who [--list] [--find] [--answered] [\"<name>\"]   say which of the people the web shows under a name is your relative, and answer the questions about names and families; --answered lists your answers, each with the command that takes it back\n"
             + "              genealogy names <person>                         a person's names over their life (at birth, by marriage, by adoption, as heir), with the years of each, and the families they belonged to\n"
             + "              genealogy family [<family> | <family name>]      with nothing, the families in your library; with a family, its heads, members and branches, and where each member came from and went to; with a family name alone, every family of that name and who bore the name when\n"
             + "              genealogy summary [--all] [--out <file.md>]      what the sources settle about your close family, each line with where it comes from, then the people easy to mix up, where your notes and the sources disagree, and what is not settled; --all adds everyone the library places in your family\n"
             + "              genealogy tidy [--yes] [--apart 2,5]             people who are in twice because their name was written two ways: shows them with what else agrees, and joins them when you say yes; the numbers after --apart are written down as two people\n"
             + "              genealogy check [accept <code> \"<why>\" | reopen <code>]   what cannot be true in the family's claims: dates, parents, same names; accept keeps one you looked at off the list\n"
             + "              genealogy source <file name or address>          every fact that rests on one source, and which of them have no other source\n"
             + "              genealogy different \"<name>\" \"<other>\" --because \"<why>\" [--undo]   two people the check or tidy took for one: written down, and not asked about again\n"
             + "              genealogy related \"<name>\" \"<other>\"             how two people are related, worked out from the parent and marriage claims\n"
             + "              genealogy split \"<name>\" --as \"<name (born 1851)>\" --claims F-…,F-…   two people of one name: move these claims to the second\n"
             + "              genealogy link [--open] [--choices]              which names and mentions are one person by the evidence, each with how sure and why; the model is asked the readings and choices the evidence leaves to it; --open lists the mentions that could be several people, --choices what the model chose\n"
             + "              genealogy hold <person> [<number> --until \"<what it waits for>\"] [--release <number>]   keeps one question about a person out of the searches while it waits for a record you asked for; the name alone lists the person's questions with their numbers\n"
             + "              genealogy import <file.ged> [--dry]   the people, families and facts of a GEDCOM family tree file, filed as drafts for you to check; --dry only says what the import would do, and who in the file may already be in your library\n"
             + "              genealogy export <person>     the family around a person as a GEDCOM file, which other family tree programs read; everyone is written in full, the living too";
    }

    private List<String> families = List.of();
    /** How many Geni profiles one read may take, walking outward from the first through its relatives. */
    private int steps = 1;
    /** A list of pages says how to go on once, after the last page. */
    private boolean inList = false;
    /** A whole folder is being read: the files in it do not each say what comes next. */
    private boolean inFolder = false;
    /** What the person wrote beside an address in a list of pages ("this is about my grandfather"): told to the reader, and kept on the claims. */
    private String note = "";
    /** The list of addresses being read: the list of reads names it beside each page and Geni profile read from it. Null when none. */
    private Path readingList = null;

    /** read <file> [--by who] · tell <sentences…> [--by who]: a family's own account into people and draft claims. */
    private Integer account(LibraryStore store, String[] args) throws Exception {
        String by = "";
        List<String> rest = new ArrayList<>();
        List<String> families = new ArrayList<>(), skip = new ArrayList<>(), only = new ArrayList<>();
        for (int i = 3; i < args.length; i++) {
            if (args[i].equals("--by") && i + 1 < args.length) by = args[++i];
            else if (args[i].equals("--steps") && i + 1 < args.length) { try { steps = Math.max(1, Integer.parseInt(args[++i])); } catch (NumberFormatException e) { steps = 1; } }
            else if (args[i].equals("--family") && i + 1 < args.length) for (String f : args[++i].split(",")) { if (!f.isBlank()) families.add(f.strip()); }
            else if (args[i].equals("--skip") && i + 1 < args.length) for (String f : args[++i].split(",")) { if (!f.isBlank()) skip.add(f.strip()); }
            else if (args[i].equals("--only") && i + 1 < args.length) for (String f : args[++i].split(",")) { if (!f.isBlank()) only.add(f.strip()); }
            else rest.add(args[i]);
        }
        this.families = families;
        // read --again with no folder: the folder the library read last, every file in it again
        boolean again = rest.contains("--again"), listOnly = rest.contains("--list");
        if (args[2].equals("read") && again && rest.stream().allMatch(a -> a.equals("--again") || a.equals("--list"))) return lastFolderAgain(store, by, families, listOnly, skip, only, Arrays.asList(args).contains("--steps"));
        // --again and --list stand anywhere, before the folder or after it
        if (args[2].equals("read")) rest.removeIf(a -> a.equals("--again") || a.equals("--list"));
        if (rest.isEmpty()) { System.err.println("usage: researchzosho genealogy read <file|url> [--by \"who wrote it\"] [--family 髙橋,Takahashi]\n       researchzosho genealogy tell \"<what you know, in sentences>\" [--by \"your name\"]"); return 2; }
        String givenBy = by;
        Path file;
        if (args[2].equals("read") && Files.isDirectory(Path.of(rest.get(0)))) return folder(store, Path.of(rest.get(0)), by, families, listOnly, again, skip, only);
        if (args[2].equals("read") && rest.get(0).matches("(?i)https?://.+")) {
            if (GeniFamily.guidOf(rest.get(0)) != null) return geni(store, rest.get(0));
            // a page that tells a family (an encyclopedia article's 家族 section, a local society's page): read like any account, and cited as that page
            Object[] got = Corpus.addUrl(store, rest.get(0), "family");
            String[] page = RawCapture.read((Path) got[0]);
            if (by.isBlank()) by = String.valueOf(got[1]).isBlank() ? rest.get(0) : got[1] + " (" + URI.create(page[0]).getHost() + ")";
            int rc = fileAccount(store, page[2], page[0], by);
            if (rc == 0) FamilyReads.readAddress(store, page[0], FamilyReads.PAGE, readingList == null ? "" : readingList.toString(), "", 0, givenBy);
            return rc;
        }
        if (args[2].equals("read")) {
            file = Path.of(rest.get(0));
            if (!Files.isRegularFile(file)) { System.err.println("no such file: " + file); return 2; }
            // a text file that is nothing but web addresses is a list of pages to read, one after the other
            List<String> urls = urlList(file);
            if (!urls.isEmpty()) {
                int n = 0, failed = 0;
                FamilyAccount.Unanswered none = null;
                inList = true;
                Path listWas = readingList;
                readingList = file.toAbsolutePath().normalize();
                try {
                for (String entry : urls) {
                    String url = entry.contains("\t") ? entry.substring(0, entry.indexOf('\t')) : entry;
                    this.note = entry.contains("\t") ? entry.substring(entry.indexOf('\t') + 1) : "";
                    System.out.println("page " + (++n) + " of " + urls.size() + ": " + url + (note.isEmpty() ? "" : "  (" + note + ")"));
                    try {
                        String subject;
                        if (searchResults(url)) {
                            // a search engine's own page cannot be read by a program: the library runs the same search itself and reads the first pages it finds
                            String q = searchQuery(url);
                            if (q.isBlank()) { System.out.println("  This is a search engine's page and it names no search words, so the library skips it."); continue; }
                            System.out.println("  This is a search engine's page, which a program cannot read. The library runs the same search itself, for \"" + q + "\", and reads the first " + SEARCH_PAGES + " pages it finds:");
                            List<FamilyIdentity.Page> found = FamilyWho.liveSearch().of(q);
                            // a page from the search is read only when its TITLE names the person the search is for: a search engine answers a query
                            // it did not understand with pages about anything at all, and a word of the query ("family tree") is in many of them
                            List<String> familyNames = families.isEmpty() ? FamilyAsking.filing(() -> FamilyFolder.familyNames(store)) : families;
                            Set<String> held = new LinkedHashSet<>();
                            for (Graph.Node pn : FamilyAsking.filing(() -> FamilyPeople.view(store)).nodes()) if ("person".equals(pn.kind())) { held.add(pn.label()); held.addAll(pn.aliases()); }
                            String heldNote = this.note; this.note = "";   // your note is about the address you gave, not about what a search returned
                            int read = 0;
                            for (FamilyIdentity.Page pg : found) {
                                if (read >= SEARCH_PAGES) break;
                                if (urls.stream().anyMatch(e -> e.startsWith(pg.url())) || searchResults(pg.url())) continue;
                                try {
                                    Object[] got = Corpus.addUrl(store, pg.url(), "family", ContentPolicy.defaults());   // a page the library's own search found, not an address the person gave
                                    String[] page = RawCapture.read((Path) got[0]);
                                    if (!searchResultAboutFamily(page[1], q, held, familyNames)) { System.out.println("    " + pg.url() + "  (its title names nobody of the family: skipped)"); continue; }
                                    System.out.println("    " + pg.url());
                                    fileAccount(store, page[2], page[0], String.valueOf(got[1]).isBlank() ? pg.url() : got[1] + " (" + URI.create(page[0]).getHost() + ")");
                                    FamilyReads.readAddress(store, page[0], FamilyReads.PAGE, readingList.toString(), "", 0, "");
                                    read++;
                                } catch (FamilyAccount.Unanswered u) { throw u; }   // said once, for the address in the list
                                catch (Exception e) { System.out.println("    " + why(e, "could not be read: ")); }
                            }
                            this.note = heldNote;
                            if (read == 0) System.out.println("  The search found no page about the family that the library could read.");
                            subject = q;
                        }
                        else if (GeniFamily.guidOf(url) != null) { if (geni(store, url) != 0) failed++; subject = geniName(url); }
                        else {
                            Object[] got = Corpus.addUrl(store, url, "family");
                            String[] page = RawCapture.read((Path) got[0]);
                            fileAccount(store, page[2], page[0], by.isBlank() ? (String.valueOf(got[1]).isBlank() ? url : got[1] + " (" + URI.create(page[0]).getHost() + ")") : by);
                            FamilyReads.readAddress(store, page[0], FamilyReads.PAGE, readingList.toString(), "", 0, "");
                            subject = String.valueOf(got[1]).isBlank() ? url : String.valueOf(got[1]);
                        }
                        // what you wrote beside the address is your own account of the person the page is about, and is filed as that, apart from the page
                        if (!note.isBlank() && note.matches(".*\\p{L}.*")) {
                            String told = note;
                            this.note = "";
                            System.out.println("  What you wrote beside this address is filed as your own account: \"" + told + "\"");
                            // a note that changed: the facts the old note gave are replaced by the new note's
                            int changed = FamilyAccount.noteChanged(store, "told://link-note/" + url, told);
                            if (changed > 0) System.out.println("  The note beside this address changed, so the " + (changed == 1 ? "fact the old note gave is" : changed + " facts the old note gave are") + " set aside as replaced by what it says now.");
                            fileAccount(store, "About " + subject + ": " + told, "told://link-note/" + url, "the owner of this library");
                            this.note = told;
                        }
                    } catch (FamilyAccount.Unanswered u) {
                        failed++; none = u; silent = u;
                        System.out.println("  " + u.said("this page") + " It did not mark the page as read.");
                        if (u.unreachable()) break;   // every other page would wait for the model in vain
                    } catch (Exception e) { failed++; System.out.println("  " + why(e, "could not be read: ")); }
                }
                } finally { readingList = listWas; }
                this.note = "";
                // a list inside a folder is written down by the folder read, with the folder; a list the model did not answer for is read again next time
                if (!inFolder && failed < urls.size() && none == null) FamilyReads.readFile(store, file, FamilyReads.LIST, "", givenBy, "");
                // a list inside a folder is one file of many: what comes next is said once, after the last file
                if (!inFolder) { if (none != null) { endAsking(); System.out.println("\n" + none.todo()); } else afterRead(store); }
                return failed == urls.size() || none != null ? 1 : 0;
            }
        } else {
            // what was told is kept as a file of the library, so every claim made from it has a source that can be read again
            Path dir = store.root().resolve("family");
            Files.createDirectories(dir);
            file = dir.resolve("told-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md");
            Files.writeString(file, String.join(" ", rest) + "\n", StandardCharsets.UTF_8);
        }
        if (by.isBlank()) by = args[2].equals("tell") ? "the owner of this library" : "the writer of " + file.getFileName();
        byte[] bytes = Files.readAllBytes(file);
        if (args[2].equals("read") && ImageText.isImage(bytes)) {
            int rc = pictures(store, List.of(file), by);
            if (rc == 0 && !inFolder) FamilyReads.readFile(store, file, FamilyReads.PICTURE, "", givenBy, "");
            return rc;
        }
        Path raw = Corpus.addFile(store, file, "family", false);
        if (raw == null) { System.err.println("no text could be read from " + file + "." + Corpus.whyNoText()); return 1; }
        String text = DocText.convert(bytes, file.getFileName().toString()).text();
        int rc = fileAccount(store, text, "file://" + file.toAbsolutePath().normalize(), by);
        // a file of a folder is written down by the folder read, with the folder; what was told is kept in the library, and no read brings it back
        if (rc == 0 && args[2].equals("read") && !inFolder) FamilyReads.readFile(store, file, FamilyReads.FILE, "", givenBy, String.join(",", families));
        return rc;
    }

    /** What the name of the file being read says, for the reader; "" when it says nothing. */
    private String label = "";

    /** Who translated the file being read, as the family's notes say it; "" for nobody known. A translator is not the writer. */
    private String translator = "";

    /** Whether the family's notes call the file being read its writer's own memoir, autobiography or diary. */
    private boolean memoir = false;

    /**
     * Pictures: the model reads each once, and the reading is kept as the picture's transcript. The pages of one record are read as
     * one text, in page order, so an entry that runs onto the next page stays one entry; each fact cites the pages its words are on.
     * Each claim notes whether its words were checked against a machine reading or a transcript a person accepted, and what the
     * file's name says. A transcript a person accepted is read as it stands, and the model does not read the picture again.
     */
    private Integer pictures(LibraryStore store, List<Path> pages, String by) throws Exception {
        List<FamilyTranscript.Transcript> read = new ArrayList<>();
        int unread = 0;
        for (Path file : pages) {
            String locator = "file://" + file.toAbsolutePath().normalize(), name = file.getFileName().toString();
            FamilyTranscript.Transcript t = FamilyTranscript.of(store, locator);
            String title = name.replaceAll("\\.[A-Za-z0-9]+$", "").replace('_', ' ');
            if (t == null || !t.accepted()) {
                DocText.Doc doc = DocText.convert(Files.readAllBytes(file), name);
                if (doc.text().isBlank()) { System.err.println("no text could be read from " + file + "." + (doc.kind().contains(": ") ? " " + doc.kind().substring(doc.kind().indexOf(": ") + 2) + "." : "")); unread++; continue; }
                t = FamilyTranscript.keep(store, locator, name, doc.text());
                if (!doc.title().isBlank()) title = doc.title();
            }
            RawCapture.capture(store, locator, t.text(), title, "corpus:family", "family");
            read.add(t);
        }
        if (read.isEmpty()) return 1;
        if (read.size() > 1) System.out.println("  The " + read.size() + " pages are read as one text, in this order: " + String.join(", ", read.stream().map(FamilyTranscript.Transcript::file).toList()) + ".");
        for (var t : read) System.out.println(t.accepted() ? "  The library reads the transcript of " + t.file() + " that " + t.by() + " accepted on " + t.acceptedOn() + "."
                : "  The model's reading of " + t.file() + " is kept as its transcript. Nobody has checked it yet, so the facts read from it say so. To see the reading and correct it:\n"
                + "    researchzosho genealogy transcript \"" + t.file() + "\"");
        FamilyFolder.Label named = FamilyFolder.label(read.get(0).file(), families.isEmpty() ? FamilyAsking.filing(() -> FamilyFolder.familyNames(store)) : families);
        if (named != null) System.out.println("  The file's name gives " + named.note().replaceFirst("^the file is labelled \"[^\"]*\": ", "") + ".");
        String text = String.join("\n", read.stream().map(FamilyTranscript.Transcript::text).toList());
        // each fact cites the pages its words are on: one page when they stand on one, else every page that holds a line of them
        Function<FamilyAccount.Fact, List<FamilyTranscript.Transcript>> onPages = f -> FamilyTranscript.pagesOf(read, f.quote());
        Function<FamilyAccount.Fact, List<String>> cites = f -> onPages.apply(f).stream().map(FamilyTranscript.Transcript::locator).toList();
        Function<FamilyAccount.Fact, List<Finding.Note>> notes = f -> {
            List<Finding.Note> n = new ArrayList<>(List.of(new Finding.Note(FamilyTranscript.NOTE, "family-account", LocalDate.now().toString(), FamilyTranscript.against(onPages.apply(f)))));
            if (named != null) n.add(new Finding.Note("file-label", "family-account", LocalDate.now().toString(), named.note()));
            return n;
        };
        label = named == null ? "" : named.forReader();
        // a page that could not be read leaves the record unfinished: the pages that were read are filed, and the record is read again next time
        try { int rc = fileAccount(store, text, read.get(0).locator(), by, cites, notes); return unread > 0 ? 1 : rc; } finally { label = ""; }
    }

    /**
     * transcript [<picture> [--accept | --from <file> | --edit]]: the writing in a picture as the model read it, and the person's
     * word on it. Accepting, or correcting, checks the words of every fact read from the picture against the transcript again.
     */
    private Integer transcript(LibraryStore store, String[] args) throws Exception {
        String picture = "", from = ""; boolean accept = false, edit = false, textOnly = false;
        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "--accept" -> accept = true;
                case "--text" -> textOnly = true;
                case "--edit" -> edit = true;
                case "--from" -> { if (i + 1 < args.length) from = args[++i]; }
                default -> picture = picture.isEmpty() ? args[i] : picture + " " + args[i];
            }
        }
        if (picture.isEmpty()) {
            List<FamilyTranscript.Transcript> all = FamilyTranscript.all(store);
            if (all.isEmpty()) { System.out.println("No picture has been read yet. `researchzosho genealogy read <picture>` reads the writing in a photographed or scanned page, and keeps what the model read as the picture's transcript."); return 0; }
            System.out.println("The pictures the library has read, and whether a person has checked the model's reading of each:\n");
            for (var t : all) System.out.println("  " + t.file() + "  " + (t.accepted() ? "accepted by " + t.by() + " on " + t.acceptedOn() : "the model's reading of " + t.read() + ", not checked yet"));
            System.out.println("\nTo see one, give its file name: researchzosho genealogy transcript \"<file name>\"");
            return 0;
        }
        List<String> several = new ArrayList<>();
        FamilyTranscript.Transcript t = FamilyTranscript.find(store, picture, several);
        if (t == null) {
            System.err.println(several.isEmpty() ? "The library has read no picture called \"" + picture + "\". `researchzosho genealogy transcript` lists the pictures it has read."
                    : "Several pictures match \"" + picture + "\": " + String.join(", ", several) + ". Give the whole file name.");
            return 1;
        }
        String by = System.getProperty("user.name", "the owner of this library");
        String corrected = null;
        if (!from.isEmpty()) {
            Path f = Path.of(from);
            if (!Files.isRegularFile(f)) { System.err.println("There is no file " + from + ". After --from give the file that holds your corrected transcript as plain text."); return 2; }
            corrected = Files.readString(f, StandardCharsets.UTF_8);
        } else if (edit) {
            String editor = System.getenv().getOrDefault("VISUAL", System.getenv().getOrDefault("EDITOR", ""));
            if (editor.isBlank() || console() == null) {
                System.err.println("There is no text editor to open here. Save the transcript to a file, correct it, and give it back:\n"
                        + "  researchzosho genealogy transcript \"" + t.file() + "\" --text > transcript.txt\n"
                        + "  researchzosho genealogy transcript \"" + t.file() + "\" --from transcript.txt\n"
                        + "The second command keeps your corrected text as the transcript you accepted.");
                return 2;
            }
            Path tmp = Files.createTempFile("transcript-", ".txt");
            Files.writeString(tmp, t.text() + "\n", StandardCharsets.UTF_8);
            List<String> command = new ArrayList<>(List.of(editor.strip().split("\\s+")));
            command.add(tmp.toString());
            int rc = new ProcessBuilder(command).inheritIO().start().waitFor();
            corrected = Files.readString(tmp, StandardCharsets.UTF_8);
            Files.deleteIfExists(tmp);
            if (rc != 0) { System.err.println("The editor stopped with an error, so the transcript was not changed."); return 1; }
        }
        if (corrected == null && !accept) {
            if (textOnly) { System.out.println(t.text()); return 0; }   // to save in a file and correct
            System.out.println("THE WRITING IN " + t.file() + "\n");
            System.out.println(t.accepted() ? "This transcript was accepted by " + t.by() + " on " + t.acceptedOn() + "." : "This is what the model read on " + t.read() + ". Nobody has checked it against the picture yet. A □ is a character the model could not read.");
            System.out.println("\n" + t.text() + "\n");
            var checks = FamilyTranscript.recheck(store, t);
            if (!checks.isEmpty()) System.out.println(checks.size() + (checks.size() == 1 ? " fact was" : " facts were") + " read from this picture." + (t.accepted() ? "" : " Their words were checked only against this reading."));
            System.out.println("\nWhat you can do:\n"
                    + "  researchzosho genealogy transcript \"" + t.file() + "\" --accept\n      says the reading above is right, as it stands.\n"
                    + "  researchzosho genealogy transcript \"" + t.file() + "\" --edit\n      opens the reading in your text editor. What you save is kept as the transcript you accepted.\n"
                    + "  researchzosho genealogy transcript \"" + t.file() + "\" --text > corrected.txt\n      saves the reading in the file corrected.txt, for you to correct in any text editor.\n"
                    + "  researchzosho genealogy transcript \"" + t.file() + "\" --from corrected.txt\n      keeps the text in the file corrected.txt as the transcript you accepted.\n"
                    + "Either way, the library then checks the words of each fact read from this picture against the transcript again.");
            return 0;
        }
        t = FamilyTranscript.accept(store, t, corrected, by);
        var checks = FamilyTranscript.noteRecheck(store, t);
        System.out.println("The transcript of " + t.file() + " is kept as accepted by " + by + ".");
        List<FamilyTranscript.Recheck> gone = checks.stream().filter(r -> !r.holds()).toList();
        if (checks.isEmpty()) System.out.println("No fact has been read from this picture yet.");
        else if (gone.isEmpty()) System.out.println("The words of all " + checks.size() + (checks.size() == 1 ? " fact" : " facts") + " read from this picture are in the transcript.");
        else {
            System.out.println((checks.size() - gone.size()) + " of the " + checks.size() + " facts read from this picture have their words in the transcript. " + (gone.size() == 1 ? "The words of this one are" : "The words of these " + gone.size() + " are") + " not in it any more:");
            for (var r : gone) System.out.println("  " + r.claim().id() + "  " + r.claim().title() + "\n      the words were: \"" + Acquisitions.compress(r.quote(), 80) + "\"");
            System.out.println("To stop using one of them, give its code: researchzosho retire " + gone.get(0).claim().id());
        }
        if (corrected != null) System.out.println("To read the facts in your corrected transcript, read the picture again. The library reads your transcript, and the model does not read the picture again:\n"
                + "  researchzosho genealogy read \"" + t.locator().replaceFirst("^file://", "") + "\"");
        return 0;
    }

    /** The name in a Geni address: /people/Genzaburo-Endo/4304… → "Genzaburo Endo". */
    static String geniName(String url) {
        Matcher m = Pattern.compile("/people/([^/]+)/\\d+").matcher(url);
        return m.find() ? URLDecoder.decode(m.group(1), StandardCharsets.UTF_8).replace('-', ' ') : url;
    }

    /** How many of a search's pages are read when a search engine's address is in the list. */
    static final int SEARCH_PAGES = 3;

    /** The words searched for, from a search engine's address: the q parameter. */
    static String searchQuery(String url) {
        Matcher m = Pattern.compile("[?&](?:q|query|p|wd|text)=([^&]+)").matcher(url);
        return m.find() ? URLDecoder.decode(m.group(1).replace('+', ' '), StandardCharsets.UTF_8).strip() : "";
    }

    /**
     * Whether a page a search engine returned is about the family, by its TITLE alone: the title carries a name the library holds for
     * somebody in the family that the query also names; failing such a name in the query, a family name. The text is not looked at: a
     * word of the query such as "family tree" stands in pages about anybody, and a search engine answers a query it did not understand
     * with pages about anything at all.
     */
    public static boolean searchResultAboutFamily(String title, String query, Collection<String> heldNames, Collection<String> familyNames) {
        String t = KanjiForms.modern(title == null ? "" : title).replaceAll("[\\s　]+", "").toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return false;
        // the names the query asks about: those the library holds that the query writes, whole, in any spacing
        String q = KanjiForms.modern(query == null ? "" : query).replaceAll("[\\s　]+", "").toLowerCase(Locale.ROOT);
        List<String> wanted = new ArrayList<>();
        for (String h : heldNames) { String k = KanjiForms.modern(h).replaceAll("[\\s　]+", "").toLowerCase(Locale.ROOT); if (k.length() >= 2 && q.contains(k)) wanted.add(k); }
        if (wanted.isEmpty()) for (String f : familyNames) { String k = KanjiForms.modern(f).replaceAll("[\\s　]+", "").toLowerCase(Locale.ROOT); if (k.length() >= 2) wanted.add(k); }
        for (String w : wanted) if (t.contains(w)) return true;
        return false;
    }

    /** A search engine's results page: google, bing, duckduckgo, yahoo, baidu, yandex with a search path. */
    static boolean searchResults(String url) {
        try {
            URI u = URI.create(url);
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT), path = u.getPath() == null ? "" : u.getPath();
            return host.matches("(www\\.)?(google\\.[a-z.]+|bing\\.com|duckduckgo\\.com|search\\.yahoo\\.[a-z.]+|baidu\\.com|yandex\\.[a-z]+)") && (path.startsWith("/search") || path.equals("/") || path.startsWith("/s") || path.startsWith("/html"));
        } catch (RuntimeException e) { return false; }
    }

    /** The addresses in a file whose every line is a web address (blank lines and # comments aside); empty when it is any other kind of file. */
    public static List<String> urlList(Path file) {
        try {
            if (Files.size(file) > 200_000) return List.of();
            List<String> out = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String l = line.strip();
                if (l.isEmpty() || l.startsWith("#")) continue;
                // an address, and after it, if the person likes, "# what this page is about"
                Matcher m = Pattern.compile("(?i)^(https?://\\S+)(?:\\s+#\\s*(.*))?$").matcher(l);
                if (!m.find()) return List.of();
                out.add(m.group(2) == null || m.group(2).isBlank() ? m.group(1) : m.group(1) + "\t" + m.group(2).strip());
            }
            return out;
        } catch (Exception e) { return List.of(); }
    }

    private static volatile FamilyAccount.Model reader = null;

    /** Tests and a host with its own model set the family reader's model; null restores the library's own. */
    public static void useReader(FamilyAccount.Model m) { reader = m; }

    private Integer fileAccount(LibraryStore store, String text, String locator, String by) throws Exception {
        return fileAccount(store, text, locator, by, f -> List.of(locator), f -> List.of());
    }

    /** {@code cites} and {@code notes}: the sources and the notes of each fact's claim, where they differ from fact to fact. */
    private Integer fileAccount(LibraryStore store, String text, String locator, String by, Function<FamilyAccount.Fact, List<String>> cites, Function<FamilyAccount.Fact, List<Finding.Note>> notes) throws Exception {
        FamilyAccount.Model model = reader;
        FamilyAccount.YesNo judge = null;
        if (model == null) {
            String address = Config.get("RESEARCHZOSHO_DRIVE", "http://localhost:8200");
            var drive = new DriveClient(address, Config.get("RESEARCHZOSHO_MODEL", "local-model"));
            DeclineJudge declines = DeclineJudge.of(drive);
            model = prompt -> {
                var msgs = new ObjectMapper().createArrayNode();
                msgs.addObject().put("role", "user").put("content", prompt);
                String out = drive.classify(msgs, 8000);
                // no answer at all is not an empty reply: the read of this text ends, and the file is not written down as read
                IOException none = DriveClient.lastClassifyUnanswered();
                if (none != null && out.isBlank()) throw new FamilyAccount.Unanswered(address, none);
                if (!Declines.hasJson(out)) declines.raise(drive.model(), "read what a family account says about the people in it, as JSON", out);   // a decline is said, never read again in halves
                return out;
            };
            // the typed yes/no for the table quotes, from the same server, when it gives token probabilities (llama.cpp does; Bedrock does not)
            // a check that got no answer is the model's silence, as for the read itself: said the same way, and the read of this text ends
            judge = drive.givesTokenProbabilities() ? q -> {
                try { return new Judge(drive).noul("", q).p("yes"); }
                catch (Judge.NoAnswer n) { throw new FamilyAccount.Unanswered(address, n.getCause()); }
            } : null;
        }
        // a web page is nobody's "I": its title and host are the source of what it says, never a person in it
        boolean page = locator.matches("(?i)https?://.+");
        String translatedBy = translator;
        // a book or a long document nobody said the writer of: its first pages are asked once whom it is by
        if (!page && locator.startsWith("file:") && by.startsWith("the writer of ") && FamilyAccount.Voice.bookish(locator, text)) {
            FamilyAccount.Byline b = FamilyAccount.byline(text, model);
            if (b != null && !b.writer().isBlank()) {
                by = b.writer();
                if (translatedBy.isBlank()) translatedBy = b.translator();
                if (!quiet) System.out.println("  Its first pages say it is by " + by + " (\"" + Acquisitions.compress(b.quote(), 90) + "\"), so the library reads it as " + by + "'s account: \"my grandfather\" in it is " + by + "'s grandfather."
                        + (translatedBy.isBlank() ? "" : " " + translatedBy + " translated it, and the translator's own remarks are not " + by + "'s words."));
            }
        }
        String teller = page ? "the writer of the page " + by : (note.isBlank() ? by : by + " — the person who keeps this library notes: \"" + note + "\"") + (label.isBlank() ? "" : " — " + label);
        // a book or a long file the person says somebody wrote: its "I" may be somebody the writer quotes
        FamilyAccount.Voice voice = page ? FamilyAccount.Voice.page(by) : locator.startsWith("file:") ? FamilyAccount.Voice.of(by, locator, translatedBy) : null;
        // a book whose writer the notes name in a way its text never writes (遠藤久 for a book by Hisa Endo): its first pages say how, for the check of its "I"
        if (voice != null && !page && FamilyAccount.Voice.bookish(locator, text) && !FamilyAccount.writes(text, by)) {
            FamilyAccount.Byline b = FamilyAccount.byline(text, model);
            voice = voice.as(b == null ? "" : b.writer(), memoir);
            if (b != null && !b.writer().isBlank() && !quiet) System.out.println("  Its first pages write the writer's name as " + b.writer() + " (\"" + Acquisitions.compress(b.quote(), 90) + "\").");
        } else if (voice != null) voice = voice.as("", memoir);
        // the families the library holds, in each script it has their name in: a text that writes "the Morita family" speaks of the 森田 family
        Graph graph = FamilyAsking.filing(() -> FamilyPeople.view(store));
        var read = FamilyAccount.read(text, teller, predicates(), model, families, line -> { if (!quiet) System.out.println("  " + line); }, judge, voice, FamilyAccount.familyForms(graph));
        // filed under the one lock an answer typed beside the reading is filed under, with the questions the file raised worked out
        Set<String> before, touched;
        FamilyAccount.Outcome o;
        FamilyAsking.FILING.lock();
        try {
            before = askBefore(store);
            o = FamilyAccount.file(store, read, note.isBlank() ? by : by + " (your note: " + note + ")", cites, notes);
            touched = !quiet && FamilyAsking.on(asking) ? FamilyAccount.touched(FamilyPeople.view(store), read) : Set.of();
        } finally { FamilyAsking.FILING.unlock(); }
        if (quiet) {
            // told in the middle of a sitting: filed without the summary of a read and without its next step, which would take the next answer
            return 0;
        }
        System.out.print(found(o.people(), o.claims(), o.alreadyHeld(), o.mayBeLiving(), "this text", false, o.names(), o.families(), o.mentions(), o.linked().size()));
        for (String t : o.twoWays()) System.out.println(t);
        for (String l : o.linked()) System.out.println(l);
        if (o.sourcesAdded() > 0) System.out.println("This text was added as a further source to " + (o.sourcesAdded() == 1 ? "1 fact" : o.sourcesAdded() + " facts") + " that your library already had from somewhere else. Each of those facts now shows every source that gives it.");
        System.out.print(droppedSaid(o.dropped()));
        askAfterFiling(store, touched, before, !inList);
        if (!inList) afterRead(store);
        return 0;
    }

    /** The model's last silence in this read: it could not be reached, or it did not answer ({@link FamilyAccount.Unanswered}); null when it answered. */
    private FamilyAccount.Unanswered silent = null;

    /** What asking beside the reading has come to in this command ({@link FamilyAsking}): its asker, and how many questions wait. */
    private final FamilyAsking.Session asking = new FamilyAsking.Session();

    /** The questions open before a file is filed, where this command may ask while it reads; empty, and nothing worked out, otherwise. */
    private Set<String> askBefore(LibraryStore store) throws IOException { return quiet ? Set.of() : FamilyAsking.openNow(store, asking); }

    /**
     * After a file, a page, a Geni profile or a tree file is filed: the questions it raised about the people it touched ({@code touchedIds};
     * empty for any), which were not open {@code before}, are handed to the asker beside the reading, where a person can answer and the
     * setting allows, and the reading goes on. {@code last}: nothing more is read in this command.
     */
    private void askAfterFiling(LibraryStore store, Set<String> touchedIds, Set<String> before, boolean last) throws IOException {
        if (quiet || !FamilyAsking.on(asking)) return;
        FamilyAsking.afterFiling(store, touchedIds, before, asking, last);
    }

    /** The reading is finished: the asker puts what waits and asks once more what got no answer, and the read says how many questions wait. */
    private void endAsking() {
        String waiting = FamilyAsking.end(asking);
        if (!waiting.isEmpty()) System.out.println("\n" + waiting);
    }

    /** The people written only by a family name that new evidence now links to a person, said with the command that takes each link back. */
    private static void linkAgain(LibraryStore store) throws IOException { for (String l : FamilyMentions.again(store)) System.out.println(l); }

    /** A question at the terminal, read with {@link Interaction#readLine}; "" where nobody can answer, or at the end of the input. */
    private static String ask(String prompt) throws IOException {
        if (!Interaction.interactive()) return "";
        System.out.print(prompt);
        System.out.flush();
        String line = Interaction.readLine();
        return line == null ? "" : line;
    }

    /**
     * What a read did not file, each kind under a heading that says why: another name that is somebody else's, a fact already disputed
     * or retired, and what the text does not say in its own words; and the facts it filed the other way round from the reading, because the
     * text's own words say which is the parent. "" when nothing.
     */
    public static String droppedSaid(List<String> dropped) {
        List<String> apart = new ArrayList<>(), again = new ArrayList<>(), turned = new ArrayList<>(), kinds = new ArrayList<>(), relatives = new ArrayList<>(), index = new ArrayList<>(), rest = new ArrayList<>();
        for (String d : dropped) (d.contains(" was not kept as another name of ") || d.contains(" was not kept as another spelling of ") ? apart : d.contains(" was not filed again, because ") ? again : d.contains(FamilyAccount.TURNED) ? turned
                : d.contains(FamilyAccount.AS_THE_WORDS_SAY) || d.contains(FamilyAccount.ANCHORED) ? kinds : d.contains(FamilyAccount.OF_A_RELATIVE) ? relatives : d.startsWith("The book's index names ") ? index : rest).add(d);
        StringBuilder b = new StringBuilder();
        for (String d : index) b.append("\nFrom the book's index: ").append(d).append('\n');
        if (!kinds.isEmpty()) {
            b.append("\nThe library filed ").append(kinds.size() == 1 ? "one fact" : kinds.size() + " facts").append(" with another relation than the model gave, because the text's own words say which relation it is:\n");
            for (String d : kinds) b.append("  - ").append(d).append('\n');
        }
        if (!relatives.isEmpty()) {
            b.append("\nThe library did not keep ").append(relatives.size() == 1 ? "one name" : relatives.size() + " names").append(" as the model gave ").append(relatives.size() == 1 ? "it" : "them")
             .append(", because the words say the name belongs to a relative, or to another person of the text:\n");
            for (String d : relatives) b.append("  - ").append(d).append('\n');
        }
        if (!turned.isEmpty()) {
            b.append("\nThe library filed ").append(turned.size() == 1 ? "one fact" : turned.size() + " facts").append(" the other way round from how the model read ").append(turned.size() == 1 ? "it" : "them")
             .append(", because the text's own words say who is the parent and who is the child:\n");
            for (String d : turned) b.append("  - ").append(d).append('\n');
        }
        if (!apart.isEmpty()) {
            b.append("\nThe library did not give ").append(apart.size() == 1 ? "a person another name" : "people " + apart.size() + " other names").append(", because each such name is another person in your library or in this text, or a name the text never writes as this person's:\n");
            for (String d : apart) b.append("  - ").append(d).append('\n');
        }
        if (!again.isEmpty()) {
            b.append("\n").append(again.size() == 1 ? "One fact" : again.size() + " facts").append(" in it ").append(again.size() == 1 ? "is one" : "are ones").append(" you disputed or retired. They stay as you left them:\n");
            for (String d : again) b.append("  - ").append(d).append('\n');
        }
        if (!rest.isEmpty()) {
            b.append("\nThe library left out ").append(rest.size()).append(rest.size() == 1 ? " thing" : " things").append(" on purpose. It keeps a fact only when it can point to the exact words in the text that say it:\n");
            for (String d : rest) b.append("  - ").append(d).append('\n');
        }
        return b.toString();
    }

    /** read <folder>: everything in it, the family's own notes first and the books last, each file once. */
    /** A file is left out by --skip, or not chosen by --only: a word of its name, or its extension, whatever the case. */
    public static boolean matches(Path file, List<String> words) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return words.stream().map(w -> w.toLowerCase(Locale.ROOT).replaceFirst("^\\.", "")).anyMatch(w -> name.contains(w) || name.endsWith("." + w));
    }

    private Integer folder(LibraryStore store, Path dir, String by, List<String> families, boolean listOnly, boolean again, List<String> skip, List<String> only) throws Exception {
        var plan = FamilyFolder.plan(store, dir);
        Map<String, String> writers = FamilyFolder.writers(plan);   // "X wrote Y.pdf" in the notes: that file is X's account
        Map<String, String> translators = FamilyFolder.translators(plan);   // "Z translated Y.pdf": not the writer
        Set<String> memoirs = FamilyFolder.memoirs(plan);   // "it is his own memoir": the text speaks as its writer
        if (plan.isEmpty()) { System.err.println("nothing to read in " + dir + ": notes (.txt .md .docx), a list of links, a tree file (.ged), pictures, books (.epub .pdf)"); return 1; }
        System.out.println("The library will read these files, one after the other, in this order:");
        int n = 0;
        for (var it : plan) System.out.println("  " + (++n) + ". " + String.join(" + ", it.pages().stream().map(pg -> pg.getFileName().toString()).toList()) + "  (" + it.what() + ", " + FamilyFolder.size(it.size()) + ")" + (it.readBefore() && !again ? "  This file was read before and has not changed, so it is skipped. Add --again to read it once more." : "") + (!skip.isEmpty() && matches(it.file(), skip) ? "  Skipped: you asked to leave it out." : !only.isEmpty() && !matches(it.file(), only) ? "  Skipped: not one of the files you asked for." : ""));
        System.out.println("\nYour own notes are read first, because they tell the library who is who in your family. The books are read last. In a book the library reads only the pages that mention your family, and even so one book can take several hours. You can leave this running and come back later.");
        if (plan.stream().anyMatch(it -> it.order() == 5)) {
            List<String> namesNow = families.isEmpty() ? FamilyFolder.familyNames(store) : families;
            System.out.println("\nIn a book the library reads only the pages that mention one of your family's names. " + (namesNow.isEmpty() ? "It does not know any family names yet. It works them out from your notes and links, which are read first."
                    : (families.isEmpty() ? "As the library knows them now, these are: " : "You gave these: ") + String.join(", ", namesNow) + "." + (families.isEmpty() ? " They are worked out again before each book, from everybody read in by then. To set them yourself, add for example --family Takahashi,髙橋 at the end of the command." : "")));
        }
        if (listOnly) return 0;
        int failed = 0, left = 0; n = 0;
        boolean wasInFolder = inFolder;
        String folder = dir.toAbsolutePath().normalize().toString();
        inFolder = true;
        try {
        for (var it : plan) {
            n++;
            if (it.readBefore() && !again) continue;
            if (!skip.isEmpty() && matches(it.file(), skip)) continue;
            if (!only.isEmpty() && !matches(it.file(), only)) continue;
            System.out.println("\n" + n + " of " + plan.size() + ": " + String.join(" + ", it.pages().stream().map(pg -> pg.getFileName().toString()).toList()));
            this.note = "";   // a note written beside a link is about that link, not about the next file
            List<String> sub = new ArrayList<>(List.of("researchzosho", "genealogy", it.order() == 2 ? "import" : "read", it.file().toString()));
            String writer = by.isBlank() ? writers.getOrDefault(it.file().getFileName().toString(), "") : by;
            this.translator = translators.getOrDefault(it.file().getFileName().toString(), "");
            this.memoir = memoirs.contains(it.file().getFileName().toString());
            if (it.order() != 2 && !writer.isBlank()) { sub.add("--by"); sub.add(writer); }
            if (by.isBlank() && !writer.isBlank()) System.out.println("  Your notes say that " + writer + " wrote this, so the library reads it as their account: \"my grandfather\" in it is " + writer + "'s grandfather.");
            if (!translator.isBlank()) System.out.println("  Your notes say that " + translator + " translated this. A translator is not the writer, so the translator's own remarks are not read as the writer's words.");
            if (it.order() == 5) {
                List<String> names = families.isEmpty() ? FamilyAsking.filing(() -> FamilyFolder.familyNames(store)) : families;
                if (names.isEmpty()) { System.out.println("  skipped: the library does not know the family's names yet, and a whole book would be read for everybody in it. Give them: --family 髙橋,Takahashi"); failed++; continue; }
                System.out.println("  This is a book, so the library reads only the pages that mention one of these family names: " + String.join(", ", names) + "."
                        + (families.isEmpty() ? "\n  The library took these names from the people who are already in your library. If a name is missing or wrong, stop this with Ctrl+C and give the names yourself by adding, for example, --family Takahashi,髙橋 at the end of the command." : ""));
                sub.add("--family"); sub.add(String.join(",", names));
            }
            this.inList = true;
            Integer rc;
            try {
                if (it.pages().size() > 1) {
                    // the pages of one record: read together, as the writer's account when the notes name one
                    this.families = List.of(); this.note = "";   // as for a single picture: every name on the page is read, not only the family's
                    rc = pictures(store, it.pages(), writer.isBlank() ? "the writer of " + it.file().getFileName() : writer);
                } else rc = it.order() == 2 ? cli(store, sub.toArray(new String[0])) : it.order() == 4 ? Corpus.withPictures(() -> account(store, sub.toArray(new String[0]))) : account(store, sub.toArray(new String[0]));
            }
            catch (FamilyAccount.Unanswered u) { silent = u; rc = 1; System.out.println("\n" + u.said(it.file().getFileName().toString()) + " It did not mark the file as read."); }
            catch (Exception e) { System.out.println("  " + why(e, "could not be read: ")); rc = 1; }
            String kind = switch (it.order()) { case 2 -> FamilyReads.TREE; case 3 -> FamilyReads.LIST; case 4 -> FamilyReads.PICTURE; default -> FamilyReads.FILE; };
            if (rc != null && rc == 0) { for (Path pg : it.pages()) FamilyFolder.markRead(store, pg, kind, folder); } else failed++;
            // a model out of reach: every other file would wait for it in vain
            if (silent != null && silent.unreachable()) {
                for (var rest : plan.subList(n, plan.size())) if ((!rest.readBefore() || again) && (skip.isEmpty() || !matches(rest.file(), skip)) && (only.isEmpty() || matches(rest.file(), only))) left++;
                break;
            }
        }
        } finally { inFolder = wasInFolder; translator = ""; memoir = false; }
        if (silent != null) {
            // what was read stays read; the next steps need the model, so what to do about the model is said last
            endAsking();
            System.out.println("\n" + (left == 0 ? "" : "The library stopped here and did not read the other " + (left == 1 ? "file" : left + " files") + ", because reading a file needs the model.\n")
                    + silent.todo() + " It reads the files that are not marked as read.");
            return 1;
        }
        afterRead(store);
        return failed == plan.size() ? 1 : 0;
    }

    /**
     * read --again with no folder: the folder the library read last as a whole, from the list of reads now and in the copies resets kept,
     * every file in it read again, as {@code read <folder> --again} does. A Geni profile in a list of links in it is read with the --steps
     * the folder's earlier read had, unless you give --steps. Nothing else is read back: the plain reset keeps what came from Geni and web pages.
     */
    private Integer lastFolderAgain(LibraryStore store, String by, List<String> families, boolean listOnly, List<String> skip, List<String> only, boolean stepsGiven) throws Exception {
        String last = FamilyReads.lastFolder(store);
        if (last == null) {
            System.err.println("The library has not read a folder of your family material yet, so there is no folder to read again. This command reads a folder, your own notes first, and writes down each file it read:\n\n"
                    + "    researchzosho genealogy read <the folder with your family material>");
            return 1;
        }
        if (!Files.isDirectory(Path.of(last))) {
            System.err.println("The folder the library read last, " + last + ", is not there any more. This command reads the folder where your family material is now, your own notes first:\n\n"
                    + "    researchzosho genealogy read <the folder with your family material>");
            return 1;
        }
        if (!stepsGiven) steps = Math.max(1, FamilyReads.Index.of(store).folderSteps(last));
        System.out.println(listOnly ? "You gave no folder, so this is the folder the library read last: " + last + ". Without --list, the command reads every file in it again, also the ones it has read before and has not changed.\n"
                : "You gave no folder, so the library reads the folder it read last once more: " + last + ". It reads every file in it again, also the ones it has read before and has not changed.\n");
        return folder(store, Path.of(last), by, families, listOnly, true, skip, only);
    }

    private static String factsN(int n) { return n == 1 ? "1 fact" : n + " facts"; }

    private static String entriesN(int n) { return n == 1 ? "1 person, place, event or name" : n + " people, places, events and names"; }

    private static String waitingN(int n) { return n == 1 ? "1 waiting research question" : n + " waiting research questions"; }

    /** The line of what a reset takes out for the questions on the research waiting list written from the facts that go; "" when there are none. */
    /** The entries a read that was stopped or taken back left in the list of people, which no fact mentions any more: every reset takes them out. */
    private static String leftOverSaid(FamilyReset.Plan plan) {
        List<String> names = plan.leftOver();
        if (names.isEmpty()) return "";
        List<String> shown = new ArrayList<>(names.size() > 6 ? names.subList(0, 5) : names);
        if (names.size() > 6) shown.add((names.size() - 5) + " more");
        return "  " + entriesN(names.size()) + " that no fact mentions any more, left by a read that was stopped or taken back before: " + FamilyReads.and(shown) + ".\n";
    }

    private static String waitingSaid(int n) {
        if (n == 0) return "";
        return "  " + (n == 1 ? "1 question on the research waiting list came from those facts. The next researchzosho genealogy research writes it again.\n"
                : n + " questions on the research waiting list came from those facts. The next researchzosho genealogy research writes them again.\n");
    }

    /** Where the copy of what a reset takes out is saved, said just before the question. */
    private static String copySaid(LibraryStore store) {
        return "\nThe library saves a copy first, in your library's family folder: " + store.root().toAbsolutePath().normalize().resolve("family");
    }

    /** What a reset took out, after "Done.": the facts, the people, places, events and names, and the waiting questions; zero counts left out. */
    private static String doneSaid(FamilyReset.Done d) {
        List<String> parts = new ArrayList<>();
        if (d.claims() > 0) parts.add(factsN(d.claims()));
        if (d.nodes() > 0) parts.add(entriesN(d.nodes()));
        if (d.waiting() > 0) parts.add(waitingN(d.waiting()));
        if (parts.isEmpty()) return "Done.";
        boolean one = parts.size() == 1 && d.claims() + d.nodes() + d.waiting() == 1;
        return "Done. " + FamilyReads.and(parts) + (one ? " was taken out." : " were taken out.");
    }

    /** The yes or no before a reset changes anything: true when the person typed y or yes, or gave --yes. What was said when it is no. */
    private static boolean resetAnswered(boolean yes) throws IOException {
        if (yes) return true;
        boolean atKeyboard = Interaction.interactive();
        String answer = ask(System.lineSeparator() + "Go ahead? (y/N) ").strip();
        if (answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes")) return true;
        System.out.println("\nNothing was changed." + (!atKeyboard ? " To go ahead without being asked, add --yes at the end of the command." : ""));
        return false;
    }

    /**
     * What stays of a family's facts whatever a reset takes, as the lines under "It keeps:" say it. {@code alsoOne} and {@code alsoMany}:
     * the line for the facts another source also gives, for one fact and for several, with %d for the number.
     */
    private static String keeps(FamilyReset.Plan plan, String alsoOne, String alsoMany) {
        int thinned = plan.thinned().size();
        StringBuilder b = new StringBuilder();
        if (thinned > 0) b.append("  ").append(thinned == 1 ? alsoOne : alsoMany.formatted(thinned)).append('\n');
        b.append(plan.kept() == 0 ? "  Everything you checked, answered or told the library yourself.\n"
                : "  " + factsN(plan.kept()) + " that you checked, answered or told the library yourself, or that the library itself marked as wrong.\n");
        if (plan.replaced() > 0) b.append("  ").append(plan.replaced() == 1 ? "1 older fact that a newer copy of a file replaced. It stays on record.\n" : plan.replaced() + " older facts that a newer copy of a file replaced. They stay on record.\n");
        if (plan.otherClaims() > 0) b.append("  ").append(factsN(plan.otherClaims())).append(" in your library that did not come from your family material.\n");
        b.append("  Your research reports, searches, saved pages and books.\n");
        b.append("  Your own files. The library never changes them.\n");
        return b.toString();
    }

    /**
     * The lines under "It keeps:" for the facts the plain reset keeps because they came from outside the folder: one line when they came
     * from one kind of place, else a line with the total and a line for each kind. {@code notBack}: why they stay, "" when no folder was read.
     */
    private static String outsideSaid(FamilyReset.Plan plan, FamilyReads.Outside stays, String notBack) {
        if (stays.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        List<String> lines = FamilyReads.breakdown(stays, stays.sources().keySet());
        String why = notBack.isEmpty() ? "" : " " + notBack;
        if (lines.size() == 1) b.append("  ").append(lines.get(0)).append(why).append('\n');
        else {
            b.append("  ").append(factsN(plan.outside().size())).append(" from ").append(FamilyReads.kinds(stays, stays.sources().keySet())).append('.').append(why).append('\n');
            for (String line : lines) b.append("    ").append(line).append('\n');
        }
        b.append("  (To take those out too, use: researchzosho genealogy reset --all)\n");
        return b.toString();
    }

    /**
     * The plain reset: it takes out what the library read from the files of your folder, which a new read of the folder brings back.
     * What it read from Geni profiles, web pages and files elsewhere stays, and the command says how much and where it came from; --all
     * takes that too.
     */
    private int resetFolder(LibraryStore store, boolean yes) throws Exception {
        var plan = FamilyReset.folder(store, ownWriters());
        FamilyReads.Index idx = FamilyReads.Index.of(store);
        FamilyReads.Outside stays = FamilyReads.outside(store, plan.outside(), idx);
        List<String> touched = new ArrayList<>(plan.claims());
        touched.addAll(plan.thinned());
        List<String> folders = FamilyReads.foldersOf(store, touched, plan.folders());
        if (folders.isEmpty()) folders = FamilyReads.topFolders(FamilyReads.everyRow(store));
        String folder = folders.isEmpty() ? "your folder" : (folders.size() == 1 ? "your folder " : "your folders ") + FamilyReads.and(folders);
        String theFolder = folders.size() > 1 ? "the folders" : "the folder";
        String notBack = "Reading " + theFolder + " again would not bring " + (plan.outside().size() == 1 ? "it" : "them") + " back.";
        if (plan.folders().isEmpty()) {
            System.out.println("The library has not read a whole folder of your family material yet, so there is nothing to take out."
                    + (stays.isEmpty() ? "\n\nTo take out everything the library read for your family, use: researchzosho genealogy reset --all\nIt shows first what it would take out, and it asks before it changes anything."
                            : " What it read from Geni, web pages and single files stays:\n\n" + outsideSaid(plan, stays, "")));
            return 0;
        }
        // what changes is decided first: with nothing to take out, the command says so and lists nothing it will not do
        int forgets = FamilyReset.forgets(store, plan);
        if (plan.claims().isEmpty() && plan.thinned().isEmpty() && plan.waiting() == 0 && forgets == 0 && plan.leftOver().isEmpty()) {
            System.out.println("There is nothing to take out of what the library read from " + folder + ". Every fact read from " + theFolder + " is one you checked, or a reset took it out already."
                    + (stays.isEmpty() ? "" : "\n\nThese stay, as they are:\n" + outsideSaid(plan, stays, notBack).stripTrailing()));
            return 0;
        }
        StringBuilder b = new StringBuilder("This takes out what the library read from " + folder + ", so you can read " + theFolder + " again from the start.\n\n");
        b.append("It will take out:\n");
        if (!plan.claims().isEmpty()) {
            b.append("  ").append(factsN(plan.claims().size())).append(" read from ").append(theFolder).append(" that you have not checked yet.\n");
            b.append("  The people, places, events, families and names that only those facts mention.\n");
            b.append("  Other names that only those files gave to people, and links the library made from those facts between a name and a person.\n");
        }
        if (forgets > 0) b.append("  The list of files the library read in ").append(theFolder).append(", so the next read reads every file again.")
         .append(FamilyReset.keepsAListRead(store, plan.folders()) ? " Your lists of web addresses stay on it, because the facts from their pages stay." : "").append('\n');
        b.append(waitingSaid(plan.waiting()));
        b.append(leftOverSaid(plan));
        b.append("\nIt keeps:\n").append(outsideSaid(plan, stays, notBack));
        b.append(keeps(plan, "1 fact that another source also gives. It stays, with that other source. Reading " + theFolder + " again adds " + (folders.size() > 1 ? "them" : "it") + " back as a source.",
                "%d facts that another source also gives. They stay, with that other source. Reading " + theFolder + " again adds " + (folders.size() > 1 ? "them" : "it") + " back as a source."));
        System.out.print(b);
        System.out.println(copySaid(store));
        if (!resetAnswered(yes)) return 0;
        var done = FamilyReset.apply(store, plan);
        StringBuilder next = new StringBuilder();
        for (String f : folders) next.append("    researchzosho genealogy read \"").append(f).append("\"\n");
        System.out.println("\n" + doneSaid(done) + (stays.isEmpty() ? "" : " The " + factsN(plan.outside().size()) + " that reading " + theFolder + " again would not bring back stay.")
                + "\nThe copy is saved in " + done.backup().toAbsolutePath().normalize() + "\n\n"
                + (folders.isEmpty() ? "Next, read your folder again. This command reads a folder, your own notes first:\n    researchzosho genealogy read <the folder with your family material>"
                        : "Next, read " + theFolder + " again:\n" + next.toString().stripTrailing()));
        return 0;
    }

    /**
     * genealogy reset --all: it takes out everything the library read for the family, from the folder, Geni, web pages and files
     * elsewhere. Before it asks, it says how many of the facts it takes out came from outside the folder and prints the commands that read
     * those sources again, a Geni profile with the largest --steps it was read with; it prints them again when it is done.
     */
    private int resetAll(LibraryStore store, boolean yes) throws Exception {
        var plan = FamilyReset.plan(store, ownWriters());
        List<FamilyReads.Row> reads = FamilyReads.everyRow(store);
        FamilyReads.Index idx = new FamilyReads.Index(reads);
        FamilyReads.Outside away = FamilyReads.outside(store, plan.claims(), idx);
        // what changes is decided first: with nothing to take out, the command says so and lists nothing it will not do
        boolean listed = !FamilyReads.rows(store).isEmpty();
        if (plan.claims().isEmpty() && plan.thinned().isEmpty() && plan.waiting() == 0 && !listed && plan.leftOver().isEmpty()) {
            System.out.println("There is nothing to take out. Every fact the library read for your family is one you checked, or there is none yet.");
            return 0;
        }
        StringBuilder b = new StringBuilder("This takes out everything the library read for your family: from your folder, from Geni, from web pages and from files elsewhere. Then you can read it all again from the start.\n\n");
        b.append("It will take out:\n");
        if (!plan.claims().isEmpty()) {
            b.append("  ").append(factsN(plan.claims().size())).append(" read from your family material that you have not checked yet.\n");
            b.append("  The people, places, events, families and names that only those facts mention.\n");
        }
        if (!plan.claims().isEmpty() || !plan.thinned().isEmpty()) b.append("  Other names the library gave to people, and links it made between a name and a person.\n");
        if (listed) b.append("  The list of everything the library read for your family, so the next reads read it all again.\n");
        b.append(waitingSaid(plan.waiting()));
        b.append(leftOverSaid(plan));
        b.append("\nIt keeps:\n");
        b.append(keeps(plan, "1 fact that a research run also found, or that you also told the library. It stays, with that source. Reading your material again adds it back as a source.",
                "%d facts that a research run also found, or that you also told the library. They stay, with that source. Reading your material again adds it back as a source."));
        System.out.print(b);
        System.out.print(FamilyReads.notBroughtBack(away, reads));
        System.out.println(copySaid(store));
        if (!resetAnswered(yes)) return 0;
        // the folders the facts were read from, each read with the --steps its lists' Geni profiles had; then what the folders do not bring back
        List<FamilyReads.Reread> again = new ArrayList<>();
        for (String f : FamilyReads.foldersOf(store, plan.claims(), FamilyReads.folders(reads))) again.add(idx.folderRead(f));
        for (FamilyReads.Reread r : away.commands()) if (!again.contains(r)) again.add(r);
        var done = FamilyReset.apply(store, plan);
        System.out.println("\n" + doneSaid(done) + "\nThe copy is saved in " + done.backup().toAbsolutePath().normalize() + "\n\n"
                + (again.isEmpty() ? "Next, read your folder again. This command reads a folder, your own notes first:\n    researchzosho genealogy read <the folder with your family material>\n"
                        : "Next, read your material again. Give these commands in this order. Each one reads one source:\n\n" + FamilyReads.commands(again, "    ")).stripTrailing());
        return 0;
    }

    /** What the copies resets kept and the library itself say of a name nobody in the family has now, after a space; "" when they say nothing. */
    private static String gone(LibraryStore store, String typed) {
        try { String said = FamilyReads.gone(store, typed); return said.isEmpty() ? "" : " " + said; }
        catch (IOException | RuntimeException e) { return ""; }
    }

    /** What went wrong, for a person: a decline as the library's plain statement, anything else as {@code otherwise} and the message. */
    static String why(Exception e, String otherwise) {
        if (e instanceof Declined d) return d.statement();
        if (e instanceof FamilyAccount.Unanswered u) return u.said("it") + " " + u.todo();
        return otherwise + e.getMessage();
    }

    /** The people the model declined a step for, said after the loop that went on with the others; {@code then}: what became of them. */
    private static void sayDeclined(Map<String, Declined> declined, String then) {
        if (declined.isEmpty()) return;
        System.out.println("\nThe model declined a step for " + (declined.size() == 1 ? "one person" : declined.size() + " people") + ". " + then + " The library went on with the others.");
        for (var e : declined.entrySet()) System.out.println("  " + e.getKey() + ": " + e.getValue().statement());
    }

    /** What one read found, in full sentences for a person who has never used the program. */
    private static String found(int people, int claims, int held, int living, String source, boolean fromGeni) { return found(people, claims, held, living, source, fromGeni, 0, 0, 0, 0); }

    /**
     * The same, with the names the read gave people ({@code names}: name claims written or backed), the families it spoke of as families,
     * the people it could write only by a family name ({@code mentions}) and how many of those the library linked to somebody ({@code linked}).
     */
    public static String found(int people, int claims, int held, int living, String source, boolean fromGeni, int names, int families, int mentions, int linked) {
        StringBuilder b = new StringBuilder("\n");
        if (people == 0 && claims == 0) {
            b.append(held > 0 ? "Everything " + source + " says about your family was already in your library from an earlier read, so nothing new was added.\n"
                    : "The library did not find anybody from a family in " + source + ", so nothing was added.\n");
            return b.toString();
        }
        b.append("The library found ").append(people).append(people == 1 ? " person in " : " people in ").append(source).append(". It wrote down ").append(claims)
         .append(claims == 1 ? " fact" : " facts").append(" that ").append(source).append(" states about them. A fact is one simple statement, for example who a person's parents are, where and when a person was born, or a job a person held.\n");
        if (held > 0) b.append(held).append(held == 1 ? " more fact was" : " more facts were").append(" already in your library from an earlier read, so ").append(held == 1 ? "it was" : "they were").append(" not added a second time.\n");
        if (living > 0) b.append("\nFor ").append(living == people ? "all " + living : living + " of these " + people).append(living == 1 ? " person" : " people").append(", nothing in your library says that they have died or places them more than ")
         .append(Gedcom.LIVING_YEARS).append(" years ago, so the library treats them as possibly still alive: a search asks about their work and public life, not about their death.\n");
        if (names > 0 || families > 0) {
            if (names > 0) b.append("It found ").append(names).append(names == 1 ? " name" : " names").append(" that people had in their lives, such as a name at birth or a name taken at marriage or adoption. ");
            if (families > 0) b.append("It found ").append(families).append(families == 1 ? " family" : " families").append(" that ").append(source).append(" speaks of as a family, such as a house or a 家. ");
            b.append("To see one person's names and families, give researchzosho genealogy names followed by the person's name in quotation marks.")
             .append(families > 0 ? " To list the families, give researchzosho genealogy family.\n" : "\n");
        }
        if (mentions > 0) {
            b.append(mentions == 1 ? "One person is" : mentions + " people are").append(" written in ").append(source).append(" only by a family name, for example \"Endo's son\", with no given name for Endo. The library keeps ")
             .append(mentions == 1 ? "that person" : "each of them").append(" as the text describes them.");
            if (linked > 0) b.append(" It linked ").append(linked == mentions ? (linked == 1 ? "that person" : "all " + linked) : linked + " of them").append(" to somebody already in your library, as said below.");
            if (linked < mentions) b.append(" To say who ").append(mentions - linked == 1 ? (mentions == 1 ? "that person is" : "the other one is") : "the others are")
             .append(", give researchzosho genealogy who. It asks you its questions one at a time.");
            b.append("\n");
        }
        if (fromGeni) b.append("\nGeni is a family tree that other people typed in. It can be wrong. Treat what came from it as a hint about where to look, not as proof.\n");
        return b.toString();
    }

    private static String whoPage() { return "http://127.0.0.1:" + Config.get("RESEARCHZOSHO_PORT", "4649") + "/who"; }

    /** What the library knows of a person, in the words of their research question: the other spellings, the dates, the parents. */
    static String knownOf(LibraryStore store, String person) {
        try {
            List<FamilyQuestions.Ask> one = FamilyQuestions.aroundEach(store, List.of(person), 0, 0, true, true, new ArrayList<>());
            if (one == null || one.isEmpty()) return "";
            // after the name as the tree writes it, which may have a bracket of its own: "Tom Hale (born 1920) (born 1920 in York; died 1990): …"
            String q = one.get(0).question(), label = one.get(0).person();
            Matcher m = Pattern.compile("^ \\((.*?)\\): ").matcher(q.startsWith(label) ? q.substring(label.length()) : q);
            return m.find() ? m.group(1) : "";
        } catch (IOException e) { return ""; }
    }

    /**
     * What somebody tells about a person during the sitting is filed like any account they give, with them as the teller. It is kept with
     * the person's question first: when the model does not answer, it waits there, and the next genealogy research files it.
     */
    void tellAbout(LibraryStore store, String person, String told) {
        boolean was = quiet;
        quiet = true;
        FamilyIdentity.Question q = null;
        try { q = FamilyIdentity.told(store, person, told); fileAccount(store, "About " + person + ": " + told, "told://who-is-who/" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")), "the owner of this library"); if (q != null) FamilyIdentity.toldFiled(store, q); }
        catch (FamilyAccount.Unanswered u) {
            System.out.println(q == null ? why(u, "The library could not file that: ")
                    : u.said("what you told") + " What you told is kept, so you do not need to tell it again. The next researchzosho genealogy research files it, once the model answers.");
        }
        catch (Exception e) { System.out.println(why(e, "The library could not file that: ")); }
        finally { quiet = was; }
    }

    /** Filing what the owner told in a who-is-who sitting: no summary and no question after it, so the sitting keeps the keyboard. */
    private boolean quiet = false;

    /**
     * who [--list] [--find] ["<name>" …]: the sitting in which you say which of the people the web shows under a name is your relative.
     */
    /** The record sites the library cannot search, each with a search for this person filled in, and how to write down a search made there by hand. */
    static void printSiteLinks(LibraryStore store, String person) throws IOException {
        List<String[]> sites = FamilyQuestions.siteLinks(FamilyPeople.view(store), person);
        if (sites.isEmpty()) return;
        System.out.println("\nSites the library cannot search itself. Each address opens a search for " + person + " there, already filled in with what your library holds:\n");
        for (String[] site : sites) System.out.println("  " + site[0] + " (" + site[1] + "):\n    " + site[2]);
        System.out.println("\nWhen you have searched one of them and found nothing, write it down, so that the library's searches know somebody looked there:\n\n"
                + "    researchzosho looked add \"" + person + "\" --where <the site's name> --what <the words you searched for>\n");
    }

    /**
     * hold <person> [<number> --until <what it waits for>] [--release <number>]: one of a person's questions waits on a record the family
     * asked for (a 戸籍, a certificate, an archive's answer). The runs leave it out until it is let go, and the reason stays with it.
     */
    public static int hold(LibraryStore store, String[] args) throws IOException {
        List<String> words = new ArrayList<>(Arrays.asList(args).subList(3, args.length));
        String until = null, release = null;
        int u = words.indexOf("--until");
        if (u >= 0) { until = String.join(" ", words.subList(u + 1, words.size())).strip(); words = new ArrayList<>(words.subList(0, u)); }
        int r = words.indexOf("--release");
        if (r >= 0) { release = r + 1 < words.size() ? words.get(r + 1) : ""; words = new ArrayList<>(words.subList(0, r)); }
        String number = words.size() > 1 && words.get(words.size() - 1).matches("\\d+") ? words.remove(words.size() - 1) : null;
        String who = String.join(" ", words).strip();
        if (who.isEmpty()) {
            System.err.println("usage: researchzosho genealogy hold <person> [<number> --until <what it waits for>] [--release <number>]\n"
                    + "  Keeps one question about a person out of the searches while it waits for a record you asked for, such as a family register from a town hall.\n"
                    + "  With the person's name alone it lists that person's questions with their numbers, and the questions already held.");
            return 2;
        }
        FamilyQuestions.Found found = FamilyQuestions.find(store, who);
        if (found.ambiguous()) { sayAmbiguous(who, found); return 1; }
        if (!found.found()) { System.err.println("There is nobody named \"" + who + "\" in your library." + (found.could().isEmpty() ? "" : " It could be: " + String.join(", ", found.could().stream().limit(8).toList()) + ".")); return 1; }
        if (!found.note().isEmpty()) System.out.println(found.note() + "\n");
        String person = found.person();
        List<FamilyQuestions.Held> held = FamilyQuestions.heldFor(FamilyQuestions.held(store), person);
        if (release != null) {
            if (!release.matches("\\d+") || Integer.parseInt(release) < 1 || Integer.parseInt(release) > held.size()) {
                System.err.println("Give the number of a held question after --release. " + (held.isEmpty() ? "No question about " + person + " is held." : "The command researchzosho genealogy hold \"" + person + "\" lists the held ones with their numbers."));
                return 2;
            }
            FamilyQuestions.Held h = held.get(Integer.parseInt(release) - 1);
            FamilyQuestions.release(store, person, h.ask());
            System.out.println("This question about " + person + " is searched for again from now on:\n  " + h.ask() + "\nThe next search for " + person + " includes it. To start that search now, give this command:\n\n    researchzosho genealogy research \"" + person + "\" --again");
            return 0;
        }
        List<String> open = FamilyQuestions.questionsOf(store, person);
        if (open == null) open = List.of();
        if (number != null) {
            int n = Integer.parseInt(number);
            if (n < 1 || n > open.size()) { System.err.println("There is no question number " + n + " for " + person + ". The command researchzosho genealogy hold \"" + person + "\" lists the questions with their numbers."); return 2; }
            if (until == null || until.isBlank()) {
                System.err.println("Say what the question waits for after --until, for example:\n\n    researchzosho genealogy hold \"" + person + "\" " + n + " --until \"the family register asked from the town hall\"\n\nThe words after --until are kept with the question, so the list shows why it waits.");
                return 2;
            }
            FamilyQuestions.Held h = FamilyQuestions.hold(store, person, open.get(n - 1), until);
            System.out.println("Held: " + h.ask() + "\nIt waits for: " + h.why() + " (since " + h.date() + ").\n"
                    + "The searches for " + person + " leave this question out until you let it go. When the record has come, the command researchzosho genealogy hold \"" + person + "\" --release <number> lets it be searched again; the number is the one in the list of held questions.");
            return 0;
        }
        if (open.isEmpty()) System.out.println("The library has no question about " + person + " that is not held.");
        else {
            System.out.println("The questions the library asks about " + person + ":\n");
            for (int i = 0; i < open.size(); i++) System.out.println("  " + (i + 1) + ". " + open.get(i));
            System.out.println("\nTo hold one of them while it waits for a record you asked for, type its number and what it waits for:\n\n    researchzosho genealogy hold \"" + person + "\" <number> --until \"<what it waits for>\"\n\n"
                    + "A held question is not searched for. It is listed under the records to request, with the reason and the date.");
        }
        if (!held.isEmpty()) {
            System.out.println("\nRecords to request for " + person + ". These questions wait for a record, so no search is made for them:\n");
            for (int i = 0; i < held.size(); i++) System.out.println("  " + (i + 1) + ". " + held.get(i).ask() + "\n       waits for: " + (held.get(i).why().isBlank() ? "(no reason given)" : held.get(i).why()) + ", since " + held.get(i).date());
            System.out.println("\nTo let one of them be searched again, for example when the record has come: researchzosho genealogy hold \"" + person + "\" --release <number>, with its number in this list.");
        }
        return 0;
    }

    private int who(LibraryStore store, String[] args) throws Exception {
        boolean list = false, find = false; List<String> names = new ArrayList<>();
        String reopen = null;
        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "--list" -> list = true;
                case "--find" -> find = true;
                case "--reopen" -> { if (i + 1 < args.length) reopen = args[++i]; else { System.err.println("Give the code of the question to ask again, as researchzosho genealogy who --answered shows it: researchzosho genealogy who --reopen <code>"); return 2; } }
                default -> { for (String one : args[i].split("\\s*[,、，;；]\\s*")) if (!one.isBlank()) names.add(one.strip()); }
            }
        }
        if (reopen != null) {
            FamilyNameQuestions.Reopened r = FamilyNameQuestions.reopen(store, reopen);
            System.out.println(r.said());
            return r.open() ? 0 : 1;
        }
        List<String> people = new ArrayList<>();
        for (String typed : names) {
            FamilyQuestions.Found found = FamilyQuestions.find(store, typed);
            if (found.found()) { if (!found.note().isEmpty()) System.out.println(found.note() + "\n"); people.add(found.person()); }
            else if (found.ambiguous()) { sayAmbiguous(typed, found); return 1; }
            else { System.out.println("Your library does not have one person named \"" + typed + "\"." + (found.could().isEmpty() ? "" : " It could be: " + String.join(", ", found.could().stream().limit(8).map(m -> "\"" + m + "\"").toList()) + ".") + (found.note().isEmpty() ? "" : " " + found.note())); return 1; }
        }
        if (find) {
            if (people.isEmpty()) { var all = FamilyQuestions.everybody(store, 6, 3, true); if (all != null) all.forEach(a -> people.add(a.person())); }
            System.out.println("The library will search the web for " + (people.size() == 1 ? "this person" : people.size() + " people") + " by name. Each name is sent to a search service, the same as typing it into a search engine. This takes about half a minute per person.\n");
            Map<String, Declined> declinedFor = new LinkedHashMap<>();
            int n = FamilyWho.findAll(store, people, FamilyWho.liveSearch(), FamilyWho.liveModel(), who -> System.out.println("  " + who + " …"), declinedFor);
            System.out.println("\nDone. The library searched for " + n + (n == 1 ? " person" : " people") + ". It skipped people it had already searched for, and people who have their own encyclopedia page.");
            sayDeclined(declinedFor, "The library has no search results to show you for them.");
        }
        // the questions about names and families first: who a person written by a family name is, one person or two, how and when a name changed
        Graph view = FamilyPeople.view(store);
        Set<String> scope = null;
        if (!people.isEmpty()) { scope = new LinkedHashSet<>(); for (String p : people) scope.add(view.nodeIdOf(p)); }
        FamilyNameQuestions.Asking asking = scope != null && scope.isEmpty() ? new FamilyNameQuestions.Asking(List.of(), List.of()) : FamilyNameQuestions.asking(store, scope);
        List<FamilyNameQuestions.Question> nameQs = FamilyNameQuestions.ordered(asking.asked());
        List<FamilyIdentity.Question> all = FamilyIdentity.all(store);
        List<FamilyIdentity.Question> ask = new ArrayList<>();
        for (FamilyIdentity.Question q : all) if (people.isEmpty() ? q.open() : people.contains(q.person())) ask.add(q);
        if (list || !Interaction.interactive()) {
            if (!nameQs.isEmpty()) {
                System.out.println("Questions about names and families that only your family can answer (" + nameQs.size() + "):\n");
                int i = 0;
                for (FamilyNameQuestions.Question q : nameQs) System.out.println("  " + (++i) + ". " + q.text() + "   [code " + q.code() + "]\n");
                System.out.println("To answer them one at a time, with what each answer does, type: researchzosho genealogy who\nOr open this page in your browser: " + whoPage() + "\nYour earlier answers, each with the command that takes it back: researchzosho genealogy who --answered\n");
            }
            String notAsked = FamilyNameQuestions.notAskedSaid(asking.notAsked().size());
            if (!notAsked.isEmpty()) System.out.println(notAsked + "\n");
            if (all.isEmpty()) { System.out.println("The library has not searched the web for anyone in your family yet. This happens at the start of: researchzosho genealogy research\nTo do only the web search now, type: researchzosho genealogy who --find"); return 0; }
            System.out.println("Web search results for the people in your family, and what you have answered:\n");
            FamilyNameHistory.Index idx = FamilyNameHistory.of(view);
            for (FamilyIdentity.Question q : all) System.out.println("  " + headingOf(view, idx, q.person()) + ": " + switch (q.state()) {
                case "confirmed" -> "confirmed: " + String.join("; ", q.yes().stream().map(FamilyIdentity.Candidate::what).toList());
                case "none" -> "you said: none of the " + q.candidates().size() + " results is this person";
                case "unsure" -> "skipped for now (" + q.candidates().size() + " possible matches)";
                case "nobody" -> "no web pages found";
                default -> "WAITING FOR YOUR ANSWER (" + q.candidates().size() + " possible matches)"; });
            long open = all.stream().filter(FamilyIdentity.Question::open).count();
            if (open > 0) System.out.println("\n" + open + (open == 1 ? " person is" : " people are") + " waiting for your answer. To answer, type: researchzosho genealogy who\nOr open this page in your browser: " + whoPage());
            return 0;
        }
        // named people at a terminal: how each name came that the library worked out as the marriage's is asked too, so one answer changes it
        if (scope != null) nameQs = FamilyNameQuestions.ordered(FamilyNameQuestions.byName(store, scope));
        if (nameQs.isEmpty() && ask.isEmpty()) { System.out.println(all.isEmpty() ? "No question about names or families waits, and the library has not searched the web for anyone in your family yet. The web search happens at the start of: researchzosho genealogy research\nTo do only the web search now, type: researchzosho genealogy who --find"
                : "There are no questions waiting for you.\nTo see your earlier answers, type: researchzosho genealogy who --list\nTo change your answer for one person, type: researchzosho genealogy who \"<their name>\""); return 0; }
        BufferedReader in = Interaction.reader();   // one reader for both sittings, so nothing typed ahead is lost between them
        if (!nameQs.isEmpty()) {
            System.out.println("First, " + (nameQs.size() == 1 ? "one question" : nameQs.size() + " questions") + " about names and families that only your family can answer. Each answer says what it will do before you give it, and you can stop at any time. Your answers are saved.");
            FamilyNameQuestions.Sat sat = FamilyNameQuestions.sitting(store, nameQs, scope, in, System.out, System.getProperty("user.name", "the owner of this library"));
            if (sat.stopped()) return 0;
            if (sat.answered() > 0) System.out.println("\nYou answered " + sat.answered() + (sat.answered() == 1 ? " question" : " questions") + " about names and families. researchzosho genealogy who --answered lists your answers, each with the command that takes it back.");
            if (ask.isEmpty()) return 0;
            all = FamilyIdentity.all(store);
            ask = new ArrayList<>();
            for (FamilyIdentity.Question q : all) if (people.isEmpty() ? q.open() : people.contains(q.person())) ask.add(q);   // answered already, or joined into another, in the names sitting
            if (ask.isEmpty()) return 0;
            System.out.println();
        }
        System.out.println("The library will show you, one person at a time, the web pages it found under that person's name, and ask you which ones are really about your relative. " + ask.size() + (ask.size() == 1 ? " question is" : " questions are") + " waiting. You can stop at any time. Your answers are saved.");
        int answered = FamilyWho.sitting(store, ask, in, System.out, person -> knownOf(store, person), (person, told) -> tellAbout(store, person, told));
        if (answered > 0) System.out.println("\nThank you. To choose whom to research, type: researchzosho genealogy research\nIt lists the people the library can research, the ones you answered for among them, and asks which to start with.");
        return 0;
    }

    /** A person as a list shows them: the latest name with the birth name beside it; the name as typed when the library has nobody of it. */
    private static String headingOf(Graph g, FamilyNameHistory.Index idx, String person) {
        String id = g.nodeIdOf(person);
        return g.node(id) == null ? person : idx.heading(id);
    }

    /**
     * The person a typed name means, as the library writes them ({@link FamilyQuestions#find}): a name typed with or without the space the
     * library writes it with, or in the old form of a character, finds the person, and when the library chose among entries it says so
     * first. The typed name itself when it finds nobody, so that the command says that in its own words. Null, said, when the name is
     * several people and the person has to pick.
     */
    private static String named(LibraryStore store, String typed) throws IOException {
        FamilyQuestions.Found found = FamilyQuestions.find(store, typed);
        if (found.ambiguous()) { sayAmbiguous(typed, found); return null; }
        if (!found.found()) return typed;
        if (!found.note().isEmpty()) System.out.println(found.note() + "\n");
        return found.person();
    }

    /** What a command says when a typed name is several people: the entries, with what tells them apart when the library knows it. */
    private static void sayAmbiguous(String typed, FamilyQuestions.Found found) {
        System.err.println(!found.note().isEmpty() ? found.note() : "Your library has " + found.could().size() + " people who could be \"" + typed + "\": " + quoted(found.could(), 8)
                + ". Give the command again with the one you mean, written exactly like that.");
    }

    /**
     * Two typed names as the two entries a command about two people works on (different, related, life --with): each found as {@link
     * #named} finds it, but never both folded into one entry. A name that is an entry of its own is kept as typed when the other name
     * would lead to the same entry: "山田太郎" and "山田 太郎" are the two entries the person named, not one. Null, said, when a name is
     * several people.
     */
    private static String[] namedPair(LibraryStore store, String a, String b) throws IOException {
        Graph g = FamilyPeople.view(store);
        Set<String> apart = Graph.differentPairs(store);
        FamilyQuestions.Found fa = FamilyQuestions.find(g, a, apart), fb = FamilyQuestions.find(g, b, apart);
        if (fa.ambiguous()) { sayAmbiguous(a, fa); return null; }
        if (fb.ambiguous()) { sayAmbiguous(b, fb); return null; }
        String x = fa.found() ? fa.person() : a, y = fb.found() ? fb.person() : b;
        if (g.nodeIdOf(x).equals(g.nodeIdOf(y)) && !g.nodeIdOf(a).equals(g.nodeIdOf(b)) && (entry(g, a) || entry(g, b))) {
            if (entry(g, a)) x = a;
            if (entry(g, b)) y = b;
            return new String[]{x, y};
        }
        // what the library chose is said, but not that the other name is another entry: the command is about the two of them
        String[] two = {x, y};
        for (int i = 0; i < 2; i++) {
            FamilyQuestions.Found f = i == 0 ? fa : fb;
            String other = g.nodeIdOf(two[1 - i]);
            if (!f.note().isEmpty() && f.also().stream().noneMatch(o -> g.nodeIdOf(o).equals(other))) System.out.println(f.note() + "\n");
        }
        return two;
    }

    /** Whether a name is an entry of the library as it is written: a person facts are about, or a name in its list of names. */
    private static boolean entry(Graph g, String name) { return g.node(g.nodeIdOf(name)) != null || g.curated().resolve(name) != null; }

    /** The live lead finder: the encyclopedia's own list of linking pages, the model's pick among them, remembered for thirty days in the library's family folder. */
    private static FamilyQuestions.Leads leadFinder(LibraryStore store) {
        DriveClient drive = new DriveClient(Config.get("RESEARCHZOSHO_DRIVE", "http://localhost:8200"), Config.get("RESEARCHZOSHO_MODEL", "local-model"));
        ObjectMapper mapper = new ObjectMapper();
        DeclineJudge declines = DeclineJudge.of(drive);
        Path kept = store.root().resolve("family").resolve("mentions.tsv");
        return (lang, title, person, years) -> {
            String key = lang + ":" + title;
            try {
                if (Files.exists(kept)) for (String l : Files.readAllLines(kept, StandardCharsets.UTF_8)) {
                    String[] c = l.split("\t", 3);
                    if (c.length == 3 && c[0].equals(key) && LocalDate.parse(c[1]).isAfter(LocalDate.now().minusDays(30))) return c[2].isBlank() ? List.of() : List.of(c[2].split(" ; "));
                }
            } catch (Exception ignored) { }
            List<String> titles = Mentions.of(lang, title, Mentions.LIVE);
            // a decline is said and never kept as "no leads"
            List<String> leads = titles.isEmpty() ? List.of() : Mentions.sift(person, years, titles, prompt -> {
                var msgs = mapper.createArrayNode(); msgs.addObject().put("role", "user").put("content", prompt);
                String out = drive.classify(msgs, 3000);
                if (!Declines.hasJson(out)) declines.raise(drive.model(), "pick the encyclopedia pages that mention a person, as JSON", out);
                return out;
            });
            try { Files.createDirectories(kept.getParent()); Files.writeString(kept, key + "\t" + LocalDate.now() + "\t" + String.join(" ; ", leads) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
            catch (IOException ignored) { }
            return leads;
        };
    }

    /** The person the family's facts say the most about: the name the next steps are written with. */
    private static String bestKnown(LibraryStore store) {
        try {
            var g = FamilyPeople.view(store);
            Map<String, Integer> links = new LinkedHashMap<>();
            for (var e : g.edges()) if (FamilyAccount.isKinship(e.predicate())) { links.merge(e.from(), 1, Integer::sum); links.merge(e.to(), 1, Integer::sum); }
            String best = links.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            return best != null && g.node(best) != null ? g.node(best).label() : "";
        } catch (Exception e) { return ""; }
    }

    /**
     * After a read: the next step is the search, said first and offered on the spot when a person is at the keyboard;
     * then what can be looked at in the meantime. Each command comes with what it does, what the name in it means and what comes out.
     */
    /** The code of the first fact waiting in the inbox, "" when none waits: an example that does something when it is typed. */
    private static String inboxCode(LibraryStore store) {
        List<Council.Row> rows = new Council(store).inbox();
        return rows.isEmpty() ? "" : rows.get(0).id().replaceFirst("^(F-\\d+).*", "$1");
    }

    private void afterRead(LibraryStore store) throws Exception {
        FamilyAsking.filing(() -> { linkAgain(store); return null; });   // what this read added may settle a person written only by a family name in an earlier one
        // who is who, worked out again with what this read added; the model is asked what the evidence leaves to it (a test's reader asks nobody)
        FamilyLinks.Model m = linkModel != null ? linkModel : reader == null ? FamilyLinks.liveModel() : null;
        FamilyLinks.update(store, m, x -> { });
        String linked = FamilyLinks.lastAskedSaid();
        if (!linked.isEmpty()) System.out.println("\n" + linked);
        endAsking();
        String best = bestKnown(store);
        boolean real = !best.isEmpty();
        String name = real ? best : "a name from your family";
        System.out.println("\nWHAT HAPPENS NEXT\n\n"
                + "So far your library knows only what is written in the material you gave it. It has not searched for anything yet.\n"
                + "The next step is to let the library search for more about the people in your family: which records exist about each person, and what each person did in their life. "
                + "It searches the web, old newspapers, archives and book collections. This is the command:\n\n"
                + "    researchzosho genealogy research\n\n"
                + "Given like this, the library goes through everybody in your family, shows you whom it would research, and asks which of them to start with. Nothing starts until you choose. "
                + "To research one person, put that person's name in quotation marks after the command, for example researchzosho genealogy research \"" + name + "\". "
                + "Add --family to that, and the library goes through that person's relatives as well, six generations back and three generations down, and asks which of them to start with.\n"
                + "The search for one person takes between fifteen minutes and one hour, and a whole family takes many days, a few people each night. It runs in the background. You do not have to wait for it.\n"
                + "To see the questions first, without starting anything, add --list at the end of the command.");
        if (Interaction.interactive() && real) {
            String answer = ask(System.lineSeparator() + "Do you want to go through your family now and choose whom to research? Type yes and press Enter. Or type one person's name to research only that person. Or just press Enter to do it later: ").strip();
            if (!answer.isEmpty() && !answer.equalsIgnoreCase("no") && !answer.equalsIgnoreCase("n")) {
                System.out.println();
                cli(store, answer.equalsIgnoreCase("yes") || answer.equalsIgnoreCase("y") ? new String[]{"researchzosho", "genealogy", "research"} : new String[]{"researchzosho", "genealogy", "research", answer});
                return;
            }
            System.out.println("\nNothing was started. You can start the search at any time with the command above.");
        }
        System.out.println("\nWHAT YOU CAN LOOK AT IN THE MEANTIME\n"
                + "\n1. Your family as a drawing.\n\n"
                + "    researchzosho genealogy tree \"" + name + "\"\n\n"
                + "  This creates a picture file named family-tree.svg in the folder you are in now. Open that file in your web browser to see the drawing. "
                + "The drawing is built around the person whose name you type. That person's box has a red border, so you can find it. Above that person are their parents, then their grandparents, and so on, up to five generations back. "
                + "Below that person are their children, then their grandchildren, and so on, up to four generations down. You can type the name of any person in your family.\n"
                + "\n2. Mistakes in what was read.\n\n"
                + "    researchzosho genealogy check\n\n"
                + "  This changes nothing. It lists things that cannot be true, for example a person who died before they were born. That usually means two people with the same name were taken for one person. It also tells you how to fix each one.\n"
                + "\n3. The list of facts, to say which ones are right.\n\n"
                + "    researchzosho inbox\n\n"
                + (inboxCode(store).isEmpty()
                   ? "  Every fact in the list has a short code in front of it. Give the command researchzosho accept with a fact's code to say the fact is right, and researchzosho dispute with the code and your reason in quotes to say it is wrong. "
                   : "  Every fact in the list has a short code in front of it, for example " + inboxCode(store) + ". With researchzosho accept " + inboxCode(store) + " you say the fact is right, and with researchzosho dispute " + inboxCode(store) + " \"the reason\" you say it is wrong. ")
                + "researchzosho accept --all says that everything in the list is right. You do not have to do this before the search.\n"
                + "\n4. One person's life story.\n\n"
                + "    researchzosho genealogy life \"" + name + "\"\n\n"
                + "  This prints everything your library knows about that person, in order of date, and where each piece of information came from. It gets longer as the searches finish.");
    }

    private Map<String, String> geniLabels;

    /** Which name each Geni profile is filed under, kept in the library's family folder from one read to the next. */
    private Map<String, String> geniLabels(LibraryStore store) throws IOException {
        if (geniLabels != null) return geniLabels;
        geniLabels = new LinkedHashMap<>();
        Path f = store.root().resolve("family").resolve("geni-people.tsv");
        if (Files.exists(f)) for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) { String[] c = l.split("\t", 2); if (c.length == 2) geniLabels.put(c[0], c[1]); }
        return geniLabels;
    }

    private void saveGeniLabels(LibraryStore store) throws IOException {
        Path f = store.root().resolve("family").resolve("geni-people.tsv");
        Files.createDirectories(f.getParent());
        StringBuilder b = new StringBuilder();
        geniLabels(store).forEach((id, label) -> b.append(id).append('\t').append(label).append('\n'));
        Files.writeString(f, b.toString(), StandardCharsets.UTF_8);
    }

    /** A Geni profile's address: read through Geni's API with the owner's login, the person and their immediate family, and with --steps the relatives' families too. */
    /** How a Geni profile is fetched: Geni's API with the owner's sign-in. A test answers instead, and then no sign-in is needed. */
    public interface GeniGet { Fetch.Result get(String url) throws Exception; }
    public static volatile GeniGet geniForTests = null;

    private Integer geni(LibraryStore store, String url) throws Exception {
        var source = RecordSources.named("geni");
        GeniGet test = geniForTests;
        String token = test != null ? "test" : source == null ? "" : Config.get(source.keyName());
        if (token == null || token.isBlank()) { System.out.println("  a Geni page shows nothing without a login. `researchzosho records login geni` signs in (it lasts a day), then read this again"); return 1; }
        GeniGet get = test != null ? test : u -> Fetch.get(u, Duration.ofSeconds(60));
        var mapper = new ObjectMapper();
        ArrayDeque<String> queue = new ArrayDeque<>(List.of("g" + GeniFamily.guidOf(url)));
        Set<String> seen = new HashSet<>(queue);
        int people = 0, claims = 0, held = 0, living = 0, taken = 0, hidden = 0, backed = 0, names = 0, families = 0, mentions = 0;
        List<String> dropped = new ArrayList<>(), linked = new ArrayList<>(), joined = new ArrayList<>();
        List<String> pages = new ArrayList<>();   // the profiles read, as the facts cite them: written down with the address given, for the reset's commands
        while (!queue.isEmpty() && taken < steps) {
            String id = queue.poll();
            if (taken++ > 0 && test == null) Thread.sleep(10_500);   // Geni allows one request every ten seconds
            if (steps > 1) System.out.println("  Geni profile " + taken + " of up to " + steps);
            var resp = get.get("https://www.geni.com/api/profile-" + id + "/immediate-family?access_token=" + token.strip());
            // Geni allows one request every ten seconds and says 429 when asked sooner, for example right after another read: wait and ask again
            for (int again = 0; resp.status() == 429 && again < 4; again++) {
                System.out.println("  Geni asks for a pause between requests. Waiting fifteen seconds.");
                Thread.sleep(15_000);
                resp = get.get("https://www.geni.com/api/profile-" + id + "/immediate-family?access_token=" + token.strip());
            }
            if (resp.status() == 401) { System.out.println("  Geni refused the sign-in (it lasts a day). Sign in again with researchzosho records login geni, then give this command again."); if (taken > 1) geniRead(store, url, pages); return taken > 1 ? 0 : 1; }
            // 403 for one profile is that profile: its owner keeps it private. The sign-in is fine, and the other relatives are still read
            if (resp.status() == 403 && taken > 1) { hidden++; continue; }
            if (resp.status() == 403) { System.out.println("  Geni does not let your sign-in see this profile: its owner keeps it private, or the sign-in has run out (researchzosho records login geni signs in again)."); return 1; }
            if (resp.status() >= 400) { System.out.println("  Geni answered " + resp.status() + " for one profile. It was skipped."); continue; }
            var answer = mapper.readTree(resp.body());
            List<String[]> renamed = new ArrayList<>();
            var read = GeniFamily.read(answer, geniLabels(store), renamed);
            String page = answer.path("focus").path("profile_url").asText(""); if (page.isBlank()) page = taken == 1 ? url : "https://www.geni.com/api/profile-" + id;
            // filed under the one lock an answer typed beside the reading is filed under
            Set<String> before, touched;
            FamilyAccount.Outcome o;
            FamilyAsking.FILING.lock();
            try {
                before = askBefore(store);
                o = FamilyAccount.file(store, read, page, note.isBlank() ? "Geni (a shared family tree)" : "Geni (a shared family tree; your note: " + note + ")");
                // a profile first met as somebody's relative, under a first and a last name, is the same person as its full name
                for (String[] r : renamed) {
                    Graph.Merged m = Graph.merge(store, r[0], r[1], "geni", "", this);
                    // every join a read makes is said, with the command that takes it back
                    if (!m.from().equals(m.to())) joined.add("Geni now gives the full name of " + r[0] + ", " + r[1] + ", so the library took the two as one person. To take it back: researchzosho graph unmerge " + FamilyNamePages.shellQuoted(r[0]));
                }
                saveGeniLabels(store);
                touched = FamilyAsking.on(asking) ? FamilyAccount.touched(FamilyPeople.view(store), read) : Set.of();
            } finally { FamilyAsking.FILING.unlock(); }
            if (!pages.contains(page)) pages.add(page);
            people += o.people(); claims += o.claims(); held += o.alreadyHeld(); living += o.mayBeLiving(); backed += o.sourcesAdded(); names += o.names(); families += o.families(); mentions += o.mentions();
            for (String d : o.dropped()) if (!dropped.contains(d)) dropped.add(d);
            for (String l : o.linked()) if (!linked.contains(l)) linked.add(l);
            askAfterFiling(store, touched, before, false);
            for (String rel : GeniFamily.relatives(answer)) { String next = rel.substring("profile-".length()); if (seen.add(next)) queue.add(next); }
            this.note = "";   // the note was about the first person
        }
        System.out.print(found(people, claims, held, living, "Geni", true, names, families, mentions, linked.size()));
        for (String l : linked) System.out.println(l);
        for (String j : joined) System.out.println(j);
        if (backed > 0) System.out.println("Geni was added as a further source to " + (backed == 1 ? "1 fact" : backed + " facts") + " that your library already had from somewhere else.");
        System.out.print(droppedSaid(dropped));
        if (hidden > 0) System.out.println("\n" + hidden + (hidden == 1 ? " relative's profile is" : " relatives' profiles are") + " kept private by " + (hidden == 1 ? "its owner" : "their owners") + " on Geni, so Geni did not show " + (hidden == 1 ? "it" : "them") + ". The person who manages " + (hidden == 1 ? "that profile" : "those profiles") + " on Geni can see " + (hidden == 1 ? "it" : "them") + ".");
        if (!queue.isEmpty()) System.out.println("\nGeni also lists " + queue.size() + " relatives of these people whose own families were not read this time. To read them as well, give the same command again and add --steps " + (steps + queue.size()) + " at the end. Geni allows one request every ten seconds, so that takes about " + Math.max(1, (queue.size() * 11) / 60) + " minute(s).");
        geniRead(store, url, pages);
        if (!inList) afterRead(store);
        return 0;
    }

    /**
     * A Geni read, in the list of reads: the address given, with its --steps and the list of addresses it stood in, and each profile read
     * on the way, which names the address given, so that a reset can say which command brings a profile's facts back. The first profile's
     * own page is the address given, however Geni writes it.
     */
    private void geniRead(LibraryStore store, String url, List<String> pages) throws IOException {
        FamilyReads.readAddress(store, url, FamilyReads.GENI, readingList == null ? "" : readingList.toString(), "", steps, "");
        String first = FamilyReads.addressKey(url);
        for (String p : pages) if (!FamilyReads.addressKey(p).equals(first)) FamilyReads.readAddress(store, p, FamilyReads.GENI, "", url, 0, "");
    }

    /**
     * research [<person> …] [--family [--up N] [--down N]] [--now N] [--queue] [--list] [--skip-living] [--again]. The people you name are
     * researched, not their relatives, and each gets one line: started now, already running, already researched, taken off the waiting
     * list and started, or not found. With no name, and for the relatives with --family, the library shows its plan, the people it would
     * research in order, and asks whom to start with: nothing starts and nothing waits by itself (the owner, 2026-09-24: "the person
     * should get to pick who to start with"). Where nobody can answer, it prints the plan and the commands, and starts and queues nothing.
     * {@code --now N} starts the first N of the plan without asking; {@code --queue} puts the rest on the nightly waiting list without asking.
     */
    private int research(LibraryStore store, String[] args) throws Exception {
        List<String> name = new ArrayList<>(); int up = 6, down = 3, now = -1; boolean list = false, withLiving = true, family = false, again = false, queue = false, oldFlag = false, reach = false;
        try {
            for (int i = 3; i < args.length; i++) {
                switch (args[i]) {
                    case "--up" -> { up = Integer.parseInt(args[++i]); reach = true; }
                    case "--down" -> { down = Integer.parseInt(args[++i]); reach = true; }
                    case "--now" -> now = Math.max(0, Integer.parseInt(args[++i]));
                    case "--list" -> list = true;
                    case "--skip-living" -> withLiving = false;
                    case "--include-living", "--include-children" -> oldFlag = true;   // what older versions asked for: the living and children are searched now unless --skip-living
                    case "--only" -> { }   // the people named and nobody else: what the command does unless --family asks for their relatives
                    case "--family" -> family = true;
                    case "--queue" -> queue = true;
                    case "--again" -> again = true;
                    default -> name.add(args[i]);
                }
            }
        } catch (RuntimeException e) {
            System.err.println("A number goes after --up, --down and --now, for example --now 2 to start the first two people on the list.");
            return 2;
        }
        if (oldFlag) System.out.println("The library searches for people who may be living, children among them, unless you add --skip-living, so --include-living and --include-children are not needed any more.\n");
        // several people: each name in its own quotation marks, or one list with commas between the names
        List<String> names = new ArrayList<>();
        for (String given : name) for (String one : given.split("\\s*[,、，;；]\\s*")) if (!one.isBlank()) names.add(one.strip());
        if (names.size() > 1 && name.size() > 1 && name.stream().noneMatch(x -> x.matches(".*[,、，;；].*")) && FamilyQuestions.find(store, String.join(" ", name)).found()) names = new ArrayList<>(List.of(String.join(" ", name)));   // one name typed without quotation marks
        if (reach && !family && !names.isEmpty()) {
            family = true;
            System.out.println("--up and --down say how far the library goes through the relatives, so it goes through the relatives of the people you named as well, as it does with --family.\n");
        }
        // each name as the library writes it: a name typed with or without a space, or with an old form of a character, finds the person
        Map<String, String> outcome = new LinkedHashMap<>();   // the person, or the name as typed when nobody was found → what happened, in the order named
        List<String> named = new ArrayList<>();
        for (String typed : names) {
            FamilyQuestions.Found found = FamilyQuestions.find(store, typed);
            if (found.found()) {
                if (!found.note().isEmpty()) System.out.println(found.note() + "\n");
                if (!named.contains(found.person())) { named.add(found.person()); outcome.put(found.person(), null); }
            } else if (found.ambiguous()) outcome.put("\"" + typed + "\"", "not started. " + (!found.note().isEmpty() ? found.note() : "Your library has " + found.could().size() + " people who could be this name: " + quoted(found.could(), 8)
                    + ". Give the command again with the one you mean, written exactly like that."));
            else {
                // a name a reset took away, or whose facts you retired: where its facts came from, and the command that brings them back
                String before = gone(store, typed);
                outcome.put("\"" + typed + "\"", "not found. Your library has nobody of that name." + before + (found.could().isEmpty()
                    ? (before.isEmpty() ? " Type the name the same way it is written in your material. The command researchzosho genealogy tree with a name draws that person's family, with the names written as the library writes them." : "")
                    : " These people have a similar name: " + quoted(found.could(), 8) + ". If you meant one of them, give the command again with that name, written exactly like that."));
            }
        }
        if (!names.isEmpty() && named.isEmpty()) { sayOutcome(outcome); return 1; }
        List<String> unknown = new ArrayList<>();
        List<FamilyQuestions.Ask> asks = names.isEmpty() ? FamilyQuestions.everybody(store, up, down, withLiving) : FamilyQuestions.aroundEach(store, named, up, down, withLiving, !family, unknown);
        for (String u : unknown) outcome.put(u, "not found in your family. The library has this name, but nothing links the person to the family yet.");
        if (!named.isEmpty()) asks = FamilyQuestions.namedFirst(store, asks, named);
        var searched = FamilyQuestions.searched(store);
        var waiting = FamilyQuestions.waiting(store);
        var parkedLines = FamilyQuestions.parked(store);
        // a record the family asked for on the decisions page ("I cannot tell"): the person is asked again, once, with that question first
        Map<String, List<String>> records = FamilyDecisions.recordQuestions(store, withLiving);
        // a waiting question written before the family disputed or retired a fact about its person may give that fact as known: when
        // it no longer begins with the question the library writes now, it is written again. Older versions wrote such questions too
        Set<String> fromTree = new HashSet<>();
        for (Frontier.Line l : Frontier.read(store)) if (l.open() && l.kind().contains(FamilyReset.FROM_TREE)) fromTree.add(l.text());
        Set<String> setAside = new HashSet<>();   // the people a disputed or retired claim is about
        {
            Graph g = FamilyPeople.view(store);
            for (Finding f : store.scanFindings().findings()) if (f.state() == Finding.State.disputed || f.state() == Finding.State.retired) setAside.addAll(FamilyQuestions.peopleOf(g, f));
        }
        List<FamilyQuestions.Ask> namedTodo = new ArrayList<>(), plan = new ArrayList<>();
        Map<String, String> fromWaiting = new LinkedHashMap<>();   // person → the question already on the waiting list for them
        Map<String, String> outdatedFor = new LinkedHashMap<>();   // person → their question on the waiting list that is written again
        Set<String> withRecord = new HashSet<>();   // people asked again for a record the family asked for
        Map<String, String> lastSaid = new LinkedHashMap<>();   // person → what became of their last search, when it was stopped or failed
        List<String> parkedPeople = new ArrayList<>();
        List<FamilyQuestions.Run> runs = FamilyQuestions.runs(store);
        int done = 0;
        for (var a : asks) {
            boolean recordNew = records.getOrDefault(a.person(), List.of()).stream().anyMatch(r -> FamilyQuestions.askedAbout(searched, a.person()).stream().noneMatch(q -> q.contains(r)));
            String line = FamilyQuestions.waitingFor(waiting, a.person());
            FamilyQuestions.Run last = FamilyQuestions.latest(runs, a.person());
            // a search that was stopped or failed did not research the person: they are on the plan again, and it says so
            boolean researchedBefore = last != null && !last.unfinished();
            if (last != null && last.unfinished()) lastSaid.put(a.person(), "The last search for this person, " + last.code() + ", " + (last.state().equals("stopped") ? "was stopped" : "failed") + (last.day().isEmpty() ? "" : " on " + last.day()) + ".");
            if (named.contains(a.person())) {
                if (last != null && Set.of("running", "queued", Jobs.OFFERED).contains(last.state())) {
                    outcome.put(a.person(), last.state().equals("running") ? "already running, as " + last.code() + ". Nothing new was started."
                            : "already waiting to start, as " + last.code() + ". It starts when the runs before it are done. Nothing new was started.");
                    continue;
                }
                // --again: the people you named are searched for once more, for example after the library learned more about their family
                if (researchedBefore && !again && !recordNew) { outcome.put(a.person(), researched(last)); continue; }
                if (recordNew) withRecord.add(a.person());
                if (line != null) fromWaiting.put(a.person(), line);
                namedTodo.add(a);
                continue;
            }
            if (researchedBefore && !recordNew) { done++; continue; }
            if (recordNew) { withRecord.add(a.person()); if (line != null) fromWaiting.put(a.person(), line); plan.add(a); continue; }
            // a parked question is not taken by the nightly research: it waits until somebody puts them back in the queue
            if (line == null && FamilyQuestions.waitingFor(parkedLines, a.person()) != null) { parkedPeople.add(a.person()); continue; }
            // written before a fact under it was disputed or retired: written again, and the old one stays on the list until the new one takes its place
            if (line != null && fromTree.contains(line) && setAside.stream().anyMatch(p -> FamilyQuestions.names(line, p)) && !Frontier.strip(line).startsWith(Frontier.strip(a.question()))) { outdatedFor.put(a.person(), line); plan.add(a); continue; }
            if (line != null) fromWaiting.put(a.person(), line);
            plan.add(a);
        }
        // a named person the family walk wrote no question for
        List<FamilyQuestions.Held> allHeld = FamilyQuestions.held(store);
        for (String p : named) if (outcome.get(p) == null && namedTodo.stream().noneMatch(a -> a.person().equals(p)))
            outcome.put(p, !FamilyQuestions.heldFor(allHeld, p).isEmpty()
                    ? "not started. Every question about this person waits for a record you asked for. The command researchzosho genealogy hold \"" + p + "\" lists them, with the command that lets one go."
                    : "not started. The library has no question to research about this person now.");
        int perNight = Crews.explorerBudget();
        if (names.isEmpty()) System.out.println("You did not name a person, so the library went through everybody in your family. It found " + asks.size() + (asks.size() == 1 ? " person." : " people."));
        else if (family) System.out.println("The library went through your family, starting from " + String.join(", ", named) + ": up to " + up + " generations back and up to " + down + " generations down" + (named.size() > 1 ? " from each of them" : "") + ". It found " + asks.size() + (asks.size() == 1 ? " person." : " people."));
        else System.out.println("The library researches the " + (names.size() == 1 ? "person" : "people") + " you named, and not their relatives. To go through their relatives as well, add --family at the end of the command.");
        if (done > 0) System.out.println("  " + done + (done == 1 ? " of them has" : " of them have") + " a search that is finished, running or about to run. They are not searched for a second time. To search for somebody once more, name that person and add --again at the end.");
        if (!outdatedFor.isEmpty()) System.out.println("  " + outdatedFor.size() + (outdatedFor.size() == 1 ? " of them has a question on the waiting list that was" : " of them have questions on the waiting list that were")
                + " written before a fact about them was disputed or retired. " + (list ? "Without --list, the library writes " + (outdatedFor.size() == 1 ? "it" : "them") + " again from what it holds now."
                : "The library writes " + (outdatedFor.size() == 1 ? "it" : "them") + " again from what it holds now. Until the new question takes its place, the old one stays on the list."));
        if (!parkedPeople.isEmpty()) System.out.println("  " + parkedPeople.size() + (parkedPeople.size() == 1 ? " of them has a question that is parked: " : " of them have a question that is parked: ") + String.join(", ", parkedPeople)
                + ". A parked question is kept, and the nightly research does not take it. The command researchzosho questions list --parked shows the parked questions with their numbers, and researchzosho questions unpark with a number puts that question back on the waiting list. "
                + "To start a search for one of these people right now instead, give this command with that person's name.");
        {
            // what waits on a record the family asked for is said here, so nobody wonders why it is not searched
            List<FamilyQuestions.Held> waitsForRecords = new ArrayList<>();
            Set<String> inReach = new HashSet<>(named); for (var a : asks) inReach.add(a.person());
            for (FamilyQuestions.Held h : allHeld) if (names.isEmpty() || inReach.contains(h.person())) waitsForRecords.add(h);
            if (!waitsForRecords.isEmpty()) {
                System.out.println("\nRecords to request. " + (waitsForRecords.size() == 1 ? "This question waits" : "These " + waitsForRecords.size() + " questions wait") + " for a record somebody asked for, so no search is made for " + (waitsForRecords.size() == 1 ? "it" : "them") + ":");
                for (FamilyQuestions.Held h : waitsForRecords) System.out.println("  " + h.person() + ": " + h.ask() + "\n      waits for: " + (h.why().isBlank() ? "(no reason given)" : h.why()) + ", since " + h.date());
                System.out.println("To let one be searched again, give the command researchzosho genealogy hold with the person's name, for example researchzosho genealogy hold \"" + waitsForRecords.get(0).person() + "\". It lists that person's held questions with the command that lets one go.");
            }
        }
        // what was told on the library's web page is filed now, as an account of the person who told it, whether or not anybody is searched for today
        if (!list) for (FamilyIdentity.Question q : FamilyIdentity.all(store)) {
            List<String> waitingToBeFiled = FamilyIdentity.toldNotFiled(q);
            if (waitingToBeFiled.isEmpty()) continue;
            System.out.println("\nOn the library's web page you told it something about " + q.person() + ". The library files that now.");
            try {
                fileAccount(store, "About " + q.person() + ": " + String.join(" ", waitingToBeFiled), "told://who-is-who/" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")), "the owner of this library");
                FamilyIdentity.toldFiled(store, q);
            } catch (FamilyAccount.Unanswered u) { System.out.println(u.said("what you told") + " It waits, and this command files it the next time it runs. " + u.todo()); }
        }
        List<FamilyQuestions.Ask> todo = new ArrayList<>(namedTodo); todo.addAll(plan);
        if (todo.isEmpty()) {
            if (!outcome.isEmpty()) sayOutcome(outcome);
            if (names.isEmpty() || family) System.out.println(asks.isEmpty() ? "\nThere is nobody to search for yet. The library searches for people it knows something about. Read in more of your material first, with researchzosho genealogy read."
                    : "\nSo there is nobody new to start a search for. When the searches have finished, they will have found new relatives. Give this command again then.\nTo see how the searches are doing: researchzosho jobs");
            return 0;
        }
        System.out.println("\nFor each person the library has written a short list of questions, one for each thing it does not know yet: who their parents were, when and where they were born, whom they married, what they did. Each question is finished when it is answered, and the report says for each one: answered, not found, or the records disagree.");
        // a named person who may be living: searched for because the owner named them, and nobody else changes
        List<String> namedLiving = namedTodo.stream().filter(FamilyQuestions.Ask::living).map(FamilyQuestions.Ask::person).toList();
        if (!namedLiving.isEmpty()) System.out.println("\nYou named " + String.join(", ", namedLiving) + " yourself, so the library searches for " + (namedLiving.size() == 1 ? "this person" : "them")
                + " although they may still be alive. About them it asks only what public sources say of their work and public life, and "
                + (namedLiving.size() == 1 ? "their name is" : "their names are") + " sent to search services on the internet. The questions name "
                + (withLiving ? "their living relatives as leads, as for everybody else" : "none of their living relatives") + ".");
        if (withLiving && plan.stream().anyMatch(FamilyQuestions.Ask::living)) System.out.println("\nSome of these people may still be alive, children among them. About them the library asks only what public sources say of their work and public life. "
                + "During a search a person's name is sent to search services on the internet, the same as when you type a name into a search engine yourself. "
                + "If you do not want that for living people, give the command with --skip-living at the end.");
        if (list) {
            List<String> notStarted = new ArrayList<>();
            outcome.forEach((who, what) -> { if (what != null) notStarted.add("  " + who + ": " + what); });
            if (!notStarted.isEmpty()) System.out.println("\nThe people you named that the library would not start now:\n" + String.join("\n", notStarted));
            System.out.println("\nThese are the questions. Nothing has been started, and nothing has been sent anywhere:\n");
            int n = 0;
            Graph held = FamilyPeople.view(store);
            for (var a : todo) {
                System.out.println("  " + (++n) + ". " + a.person() + (a.questions().isEmpty() ? ": " + a.question() : ""));
                for (String sq : a.questions()) System.out.println("        - " + sq);
                List<String[]> sites = a.living() ? List.of() : FamilyQuestions.siteLinks(held, a.person());
                if (!sites.isEmpty()) {
                    System.out.println("      Sites the library cannot search itself. Each address below opens a search for this person there, already filled in:");
                    for (String[] site : sites) System.out.println("        " + site[0] + " (" + site[1] + "): " + site[2]);
                }
            }
            System.out.println("\nTo start, give the same command again without --list at the end." + (plan.isEmpty() ? "" : " The library then asks which of the people on its list to start with."));
            return 0;
        }
        boolean atKeyboard = Interaction.interactive();
        BufferedReader in = atKeyboard ? Interaction.reader() : null;
        var protocol = new LibraryProtocol(store);
        List<String> startedLines = new ArrayList<>();
        // the people named are about to be researched: they are looked up on the web now, and asked about
        List<FamilyQuestions.Ask> readyNamed = new ArrayList<>(), heldNamed = new ArrayList<>();
        Set<String> declinedNamed = new HashSet<>();
        identify(store, namedTodo, in, atKeyboard, readyNamed, heldNamed, declinedNamed, "Their searches were not started.");
        for (String p : declinedNamed) outcome.put(p, "not started, because the model declined to look this person up. What it said is above.");
        for (var a : heldNamed) outcome.put(a.person(), "not started, because you have not said yet which of the people the web shows under this name is your relative. To answer, type: researchzosho genealogy who \"" + a.person() + "\""
                + (fromWaiting.containsKey(a.person()) ? ". Their question stays on the nightly waiting list." : ""));
        for (var a : withLeads(store, readyNamed, outcome)) {
            String job = start(store, protocol, a, fromWaiting.get(a.person()), withRecord.contains(a.person()) ? "started by name, with the record question the family asked for, as " : "started by name as ");
            startedLines.add("  " + job + "  " + a.person());
            outcome.put(a.person(), (fromWaiting.containsKey(a.person()) ? "was on the nightly waiting list. Started now, as " + job + ", and taken off the list." : "started now, as " + job + ".")
                    + (lastSaid.containsKey(a.person()) ? " " + lastSaid.get(a.person()) : ""));
        }
        if (!outcome.isEmpty()) sayOutcome(outcome);
        int added = 0, rewritten = 0, stays = 0;
        if (!plan.isEmpty()) {
            // the plan is shown before anybody is looked up on the web: a name goes out only for a person who is about to be researched
            Graph joined = FamilyPeople.view(store);
            System.out.println("\n" + (names.isEmpty() ? "THE PEOPLE THE LIBRARY CAN RESEARCH" : "THE RELATIVES THE LIBRARY CAN RESEARCH") + "\n\nThe library can research "
                    + (plan.size() == 1 ? "this person" : "these " + plan.size() + " people") + (named.isEmpty() ? "" : ", relatives of the " + (named.size() == 1 ? "person" : "people") + " you named") + ". "
                    + (plan.size() == 1 ? "" : "The people who have died come first, and among them the ones your library knows least about. ") + "Nothing is started and nothing is put on the waiting list until you say so:\n");
            for (int i = 0; i < plan.size(); i++) {
                var a = plan.get(i);
                FamilyIdentity.Question q = FamilyIdentity.forPerson(store, joined, a.person());
                System.out.println("  " + (i + 1) + ". " + FamilyNamePages.heading(joined, a.person()) + "." + (a.gaps().isEmpty() ? "" : " Not known yet: " + String.join(", ", a.gaps()) + ".")
                        + (a.living() ? " May still be alive." : "") + (fromWaiting.containsKey(a.person()) || outdatedFor.containsKey(a.person()) ? " On the nightly waiting list already." : "")
                        + (q != null && q.open() ? " Waits for your answer on who is who." : "") + (lastSaid.containsKey(a.person()) ? " " + lastSaid.get(a.person()) : ""));
            }
            List<Integer> chosen;
            if (now >= 0) chosen = firstOf(now, plan.size());
            else if (atKeyboard) chosen = pick(in, plan.size());
            else chosen = List.of();
            List<String> example = plan.stream().limit(3).map(a -> "\"" + a.person() + "\"").toList();
            long already = plan.stream().filter(a -> fromWaiting.containsKey(a.person()) || outdatedFor.containsKey(a.person())).count();
            if (now < 0 && !atKeyboard && queue) System.out.println("\nNone of these people was started now, because nobody was at the keyboard to choose. To start some of them now, give their names, each in quotation marks, for example:\n\n    researchzosho genealogy research " + String.join(" ", example));
            else if (now < 0 && !atKeyboard) {
                String base = "researchzosho genealogy research" + (named.isEmpty() ? "" : " " + String.join(" ", named.stream().map(p -> "\"" + p + "\"").toList()) + " --family")
                        + (up != 6 ? " --up " + up : "") + (down != 3 ? " --down " + down : "") + (withLiving ? "" : " --skip-living");
                System.out.println("\nNone of these people was started, and nobody new was put on the nightly waiting list, because nobody was at the keyboard to choose."
                        + (already == 0 ? "" : already == 1 ? " The one marked as on the nightly waiting list already stays on it." : " The " + already + " marked as on the nightly waiting list already stay on it.")
                        + " To start the research for some of them, give their names, each in quotation marks, for example:\n\n"
                        + "    researchzosho genealogy research " + String.join(" ", example) + "\n\n"
                        + "To start the first " + Math.min(3, plan.size()) + " on the list instead:\n\n    " + base + " --now " + Math.min(3, plan.size()) + "\n\n"
                        + "To put everybody on the list on the nightly waiting list, where the library takes " + perNight + " every night by itself:\n\n    " + base + " --queue");
            }
            List<FamilyQuestions.Ask> pickedAsks = new ArrayList<>();
            for (int i : chosen) pickedAsks.add(plan.get(i - 1));
            // the people picked are about to be researched: they are looked up on the web now, and asked about
            List<FamilyQuestions.Ask> readyPicked = new ArrayList<>(), heldPicked = new ArrayList<>();
            Set<String> declinedPicked = new HashSet<>();
            identify(store, pickedAsks, in, atKeyboard, readyPicked, heldPicked, declinedPicked, "Their searches were not started.");
            if (!heldPicked.isEmpty()) System.out.println("\nThe library did not start " + (heldPicked.size() == 1 ? "1 of the people you picked" : heldPicked.size() + " of the people you picked") + ", because you have not said yet which of the people the web shows under "
                    + (heldPicked.size() == 1 ? "that name is your relative: " : "those names are your relatives: ") + String.join(", ", heldPicked.stream().map(FamilyQuestions.Ask::person).toList()) + ". "
                    + "A question of theirs that is on the nightly waiting list stays there. To answer, type:\n\n    researchzosho genealogy who\n\nOr open this page in your browser: " + whoPage() + "\nWhen you are done, give the research command again, and pick them.");
            for (var a : withLeads(store, readyPicked, null)) {
                String p = a.person();
                String job = start(store, protocol, a, fromWaiting.getOrDefault(p, outdatedFor.get(p)), outdatedFor.containsKey(p) ? "written again and started from the list as " : "started from the list as ");
                startedLines.add("  " + job + "  " + p);
            }
            // the rest: a question on the waiting list stays there (written again when a fact under it was disputed), and a new one waits only when you say so
            List<FamilyQuestions.Ask> rest = plan.stream().filter(a -> !pickedAsks.contains(a)).toList();
            List<FamilyQuestions.Ask> fresh = rest.stream().filter(a -> !fromWaiting.containsKey(a.person()) && !outdatedFor.containsKey(a.person())).toList();
            boolean toList = queue;
            if (!toList && atKeyboard && !fresh.isEmpty()) {
                String them = pickedAsks.isEmpty() ? (fresh.size() == 1 ? "this person" : "these " + fresh.size() + " people") : "the other " + (fresh.size() == 1 ? "person" : fresh.size() + " people");
                System.out.print("\nPut " + them + " on the nightly waiting list? The library takes " + perNight + " from that list every night by itself. Type y and press Enter to put "
                        + (fresh.size() == 1 ? "them" : "them all") + " there, or just press Enter to leave them off (y/N): ");
                System.out.flush();
                String yes = in.readLine();
                toList = yes != null && (yes.strip().equalsIgnoreCase("y") || yes.strip().equalsIgnoreCase("yes"));
            }
            // the people newly put on the list are about to be researched, by the nightly research: they are looked up now, and asked about
            List<FamilyQuestions.Ask> readyFresh = new ArrayList<>(), heldFresh = new ArrayList<>();
            Set<String> declinedFresh = new HashSet<>();
            if (toList) identify(store, fresh, in, atKeyboard, readyFresh, heldFresh, declinedFresh, "They were not put on the nightly waiting list.");
            if (!heldFresh.isEmpty()) System.out.println("\n" + (heldFresh.size() == 1 ? "1 person was" : heldFresh.size() + " people were") + " not put on the nightly waiting list, because you have not said yet which of the people the web shows under "
                    + (heldFresh.size() == 1 ? "that name is your relative: " : "those names are your relatives: ") + String.join(", ", heldFresh.stream().limit(12).map(FamilyQuestions.Ask::person).toList()) + (heldFresh.size() > 12 ? " and " + (heldFresh.size() - 12) + " more" : "")
                    + ". To answer, type:\n\n    researchzosho genealogy who\n\nOr open this page in your browser: " + whoPage() + "\nWhen you are done, give the research command again.");
            Graph now2 = FamilyPeople.view(store);
            List<FamilyQuestions.Ask> toFile = new ArrayList<>();
            for (var a : rest) {
                String p = a.person();
                if (outdatedFor.containsKey(p) || (fromWaiting.containsKey(p) && withRecord.contains(p))) toFile.add(a.withQuestion(a.question() + FamilyIdentity.forQuestion(FamilyIdentity.forPerson(store, now2, p))));
                else if (fromWaiting.containsKey(p)) stays++;
            }
            toFile.addAll(readyFresh);
            for (var a : withLeads(store, toFile, null)) {
                String p = a.person();
                // the question written again takes the old one's place: the old one leaves the list only now, so it is never lost
                if (outdatedFor.containsKey(p)) { Frontier.remove(store, Set.of(outdatedFor.get(p))); rewritten++; }
                else if (fromWaiting.containsKey(p)) { Frontier.markExplored(store, fromWaiting.get(p), "written again with the record question the family asked for"); rewritten++; }
                else added++;
                store.frontier("person " + System.getProperty("user.name", "person") + " " + FamilyReset.FROM_TREE + Fields.mark(name()), a.question());
            }
            if (!fresh.isEmpty() && !toList && (atKeyboard || now >= 0)) System.out.println("\n" + (pickedAsks.isEmpty() ? (fresh.size() == 1 ? "This person was" : "These " + fresh.size() + " people were") : "The other " + (fresh.size() == 1 ? "person was" : fresh.size() + " people were"))
                    + " not put on the nightly waiting list. To research them later, give this command again and choose, or name them."
                    + (atKeyboard ? "" : " To put them on the waiting list, give the same command again with --queue at the end."));
        }
        if (!startedLines.isEmpty()) {
            System.out.println("\nTHE SEARCH HAS STARTED\n\n" + startedLines.size() + (startedLines.size() == 1 ? " search was started just now. It takes between fifteen minutes and one hour. The search has a code:\n"
                    : " searches were started just now. They run one after the other, and each one takes between fifteen minutes and one hour. Each search has a code:\n"));
            startedLines.forEach(System.out::println);
        }
        if (added + rewritten + stays > 0) {
            List<String> parts = new ArrayList<>();
            if (added > 0) parts.add(added + (added == 1 ? " person was" : " people were") + " put on the nightly waiting list just now");
            if (rewritten > 0) parts.add(rewritten + (rewritten == 1 ? " person's question was on the nightly waiting list already and was written again from what the library holds now, so it stays on the list"
                    : " people's questions were on the nightly waiting list already and were written again from what the library holds now, so they stay on the list"));
            if (stays > 0) parts.add(stays + (stays == 1 ? " person was" : " people were") + " on the nightly waiting list already and stay" + (stays == 1 ? "s" : "") + " on it");
            int total = added + rewritten + stays;
            System.out.println("\n" + String.join("; ", parts) + ". Every night the library takes " + perNight + " from that list by itself, so at this rate the list takes about " + Math.max(1, (total + perNight - 1) / perNight)
                    + " night(s). To make it take more each night, for example six: researchzosho questions budget 6");
        }
        if (startedLines.isEmpty()) return 0;
        System.out.println("\nYou do not have to keep this window open. The searching is done by the library's service in the background.\n"
                + "\nTo see which search is running and which are finished:\n\n    researchzosho jobs\n"
                + "\nWhen a search is finished, the new facts it found are added to the list you see with researchzosho inbox, and to the person's life story, which you read with researchzosho genealogy life \"<the person's name>\".\n"
                + "\nWhen all the searches are finished, give this same command once more. The searches will have found new relatives, such as a great-grandfather's parents. The library then writes questions for those new people too. This is how your tree grows further back, one generation at a time.");
        return 0;
    }

    /** The web search and the model the who-is-who lookups use; a test sets its own, and null is the live ones. */
    private static volatile FamilyIdentity.Search whoSearch = null;
    private static volatile Function<String, String> whoModel = null;

    /** For a test: the search and the model the who-is-who lookups use; null, null for the live ones. */
    public static void whoLookup(FamilyIdentity.Search search, Function<String, String> model) { whoSearch = search; whoModel = model; }

    private static FamilyIdentity.Search whoSearch() { FamilyIdentity.Search s = whoSearch; return s != null ? s : FamilyWho.liveSearch(); }
    private static Function<String, String> whoModel() { return whoSearch != null ? whoModel : FamilyWho.liveModel(); }

    /**
     * The people about to be researched, and nobody else, looked up on the web by name (a person looked up before is not looked up again),
     * and at the keyboard asked about: which of the people the web shows under the name is theirs. {@code ready}: each with the question
     * as the answers make it; {@code held}: the ones who still wait for that answer, because a search that cannot tell a relative from a
     * namesake files the namesake's life. A person the model declined to look up goes into {@code declined}, and what it said is said
     * here, followed by {@code then}.
     */
    private void identify(LibraryStore store, List<FamilyQuestions.Ask> asks, BufferedReader in, boolean atKeyboard, List<FamilyQuestions.Ask> ready, List<FamilyQuestions.Ask> held, Set<String> declined, String then) throws Exception {
        if (asks.isEmpty()) return;
        boolean[] said = {false};
        Map<String, Declined> declinedFor = new LinkedHashMap<>();
        FamilyWho.findAll(store, asks.stream().map(FamilyQuestions.Ask::person).toList(), whoSearch(), whoModel(), who -> {
            if (!said[0]) {
                System.out.println("\nFirst, the library searches the web for each of " + (asks.size() == 1 ? "them" : "these people") + " by name. This takes about half a minute per person. Many people share the same name, so "
                        + (atKeyboard ? "afterwards it asks you which search results are really about your relatives.\n" : "somebody in your family says afterwards which search results are really about your relatives.\n"));
                said[0] = true;
            }
            System.out.println("  " + who + " …");
        }, declinedFor);
        sayDeclined(declinedFor, then);
        declined.addAll(declinedFor.keySet());
        List<FamilyQuestions.Ask> left = asks.stream().filter(a -> !declinedFor.containsKey(a.person())).toList();   // the model declined for them: not researched in its place
        Graph joined = FamilyPeople.view(store);   // an answer given under a name since joined into the person is theirs
        List<FamilyIdentity.Question> open = new ArrayList<>();
        for (var a : left) { FamilyIdentity.Question q = FamilyIdentity.forPerson(store, joined, a.person()); if (q != null && q.open()) open.add(q); }
        if (!open.isEmpty() && atKeyboard) {
            System.out.println("\nThe library found web pages for " + open.size() + (open.size() == 1 ? " person" : " people") + " in your family. It cannot tell which pages are really about your relatives, so it will now ask you, one person at a time. "
                    + "Each one takes a minute or two. You can stop at any time. Your answers are saved.");
            FamilyWho.sitting(store, open, in, System.out, person -> knownOf(store, person), (person, told) -> tellAbout(store, person, told));
        }
        for (var a : left) {
            FamilyIdentity.Question q = FamilyIdentity.forPerson(store, joined, a.person());
            if (q != null && q.open()) held.add(a);
            else ready.add(a.withQuestion(a.question() + FamilyIdentity.forQuestion(q)));
        }
    }

    /** What happened to each person named, one line each, in the order they were named. */
    private static void sayOutcome(Map<String, String> outcome) {
        List<String> lines = new ArrayList<>();
        outcome.forEach((who, what) -> { if (what != null) lines.add("  " + who + ": " + what); });
        if (!lines.isEmpty()) System.out.println("\nThe " + (lines.size() == 1 ? "person" : "people") + " you named:\n" + String.join("\n", lines));
    }

    /** The line for a named person who was researched before, with the run's code and day. A stopped or failed run is no research: such a person is started again. */
    private static String researched(FamilyQuestions.Run last) {
        String again = " To research this person again, add --again at the end of the command.";
        if (last == null || last.code().isEmpty()) return "already researched." + again;
        String on = last.day().isEmpty() ? "" : " on " + last.day();
        return (last.state().equals("explored") ? "already researched by the nightly research, as " + last.code() + on + "." : "already researched, as " + last.code() + ", finished" + on + ".") + again;
    }

    /** Names in quotation marks, at most {@code most} of them. */
    private static String quoted(List<String> names, int most) { return String.join(", ", names.stream().limit(most).map(m -> "\"" + m + "\"").toList()); }

    /** One person's research started as a run in depth; the question that waited for them is marked with the run's code. Returns the code. */
    private String start(LibraryStore store, LibraryProtocol protocol, FamilyQuestions.Ask a, String waitingLine, String how) throws IOException {
        var ask = new ObjectMapper().createObjectNode(); ask.put("question", a.question()); ask.put("mode", "depth"); ask.put("field", name());
        if (!a.questions().isEmpty()) { var subs = ask.putArray("sub_questions"); for (String sq : a.questions()) subs.add(sq + " (" + a.person() + ")"); }
        ask.putObject("patron").put("did", "person").put("name", System.getProperty("user.name", "person")).put("runtime", "cli");
        String job = protocol.research(ask, new LibraryProtocol.Way("genealogy-command", "", false)).path("job_id").asText();
        if (waitingLine != null) Frontier.markExplored(store, waitingLine, how + job);
        return job;
    }

    /**
     * For the people who have an encyclopedia page of their own: the pages that mention them, listed by the library and picked among by the
     * model, added to their questions as leads. A person the model declined for is left out, and named in {@code outcome} when named.
     */
    private List<FamilyQuestions.Ask> withLeads(LibraryStore store, List<FamilyQuestions.Ask> asks, Map<String, String> outcome) throws IOException {
        if (asks.isEmpty()) return asks;
        boolean[] said = {false};
        Map<String, Declined> declinedFor = new LinkedHashMap<>();
        List<FamilyQuestions.Ask> out = FamilyQuestions.withLeads(store, asks, leadFinder(store), who -> {
            if (!said[0]) { System.out.println("\nSome of these people have a page in an encyclopedia. The library is asking the encyclopedia which other pages mention them: an affair or an event a person had a part in is usually told on its own page, not on theirs. This takes up to a minute for each such person."); said[0] = true; }
            System.out.println("  " + who + " …");
        }, declinedFor);
        sayDeclined(declinedFor, "Their searches were not started.");
        if (outcome != null) for (String p : declinedFor.keySet()) outcome.put(p, "not started, because the model declined to pick the encyclopedia pages that mention this person. What it said is above.");
        return out;
    }

    /** The first {@code n} of {@code size} places, counted from 1. */
    static List<Integer> firstOf(int n, int size) { List<Integer> out = new ArrayList<>(); for (int i = 1; i <= Math.min(n, size); i++) out.add(i); return out; }

    /** Asks which people on the plan to start with, until the answer can be read; Enter, or no answer, is nobody. */
    private static List<Integer> pick(BufferedReader in, int size) throws IOException {
        System.out.print("\nWhich of them should the library research now? Type their numbers with commas between them, for example " + (size >= 3 ? "1,3" : "1") + ". Type all for everybody on the list. Or just press Enter to start nobody now: ");
        for (int tries = 0; ; tries++) {
            System.out.flush();
            String answer = in.readLine();
            List<Integer> chosen = chosen(answer, size);
            if (chosen != null) return chosen;
            if (tries >= 2) { System.out.println("The library could not read that, so it started nobody."); return List.of(); }
            System.out.print("The library could not read that. Type numbers from 1 to " + size + " with commas between them, for example 1,3, a range such as 2-4, or all, or just press Enter to start nobody: ");
        }
    }

    /**
     * The places an answer names: "1,3,5", "2-4", "2 - 4", "2～4", "2〜4", "all"; empty for no answer, Enter or none; null when it cannot
     * be read, so that the person is asked again: a number not on the list, a range the wrong way round, a number too long to be one.
     */
    static List<Integer> chosen(String answer, int size) {
        String a = answer == null ? "" : Normalizer.normalize(answer, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
        if (a.isEmpty() || a.equals("none") || a.equals("no") || a.equals("n")) return List.of();
        if (a.equals("all") || a.equals("everybody") || a.equals("everyone")) return firstOf(size, size);
        a = a.replaceAll("\\s*[-~〜～‐–—]\\s*", "-");   // a range mark, with or without spaces around it
        Set<Integer> out = new TreeSet<>();
        for (String part : a.split("[,、，;；\\s]+")) {
            if (part.isEmpty()) continue;
            Matcher m = Pattern.compile("(\\d{1,6})(?:-(\\d{1,6}))?").matcher(part);
            if (!m.matches()) return null;
            int from = Integer.parseInt(m.group(1)), to = m.group(2) == null ? from : Integer.parseInt(m.group(2));
            if (from < 1 || to > size || to < from) return null;
            for (int i = from; i <= to; i++) out.add(i);
        }
        return new ArrayList<>(out);
    }

    @Override public Integer cli(LibraryStore store, String[] args) throws Exception {
        if (args.length < 3) { System.err.println("usage: researchzosho genealogy read <file|url> | tell <what you know…> | tree <person> | life <person> | names <person> | family [<family>] | summary [--all] | research <person> | hold <person> | transcript <picture> | check | source <file or address> | tidy | different <a> <b> | related <a> <b> | reset | split | link | import <file.ged> | export <person>"); return 2; }
        // a reset that was stopped before it had finished is finished first, so that no command works on a library taken halfway apart
        String finished = FamilyReset.finishStopped(store);
        if (!finished.isEmpty()) System.err.println(finished + "\n");
        // said once, in a library that holds family work, and never on a run that changes nothing (--dry)
        if (upgradedBefore(store) && !Files.exists(namesToldFile(store)) && Fields.holdsWork(store, this) && !Arrays.asList(args).contains("--dry")) {
            String n = namesNoteOnce(store);
            if (n != null && !args[2].equals("who")) System.err.println(n + "\n");
        }
        if (args[2].equals("read") || args[2].equals("tell")) {
            silent = null;
            boolean ended = false;
            try { Integer rc = account(store, args); ended = true; return rc; }
            catch (FamilyAccount.Unanswered u) {
                // one file, one page or what was told: nothing of it was filed, and it is not written down as read
                String target = args.length > 3 ? args[3] : "";
                boolean file = args[2].equals("read") && !target.matches("(?i)https?://.+");
                String what = args[2].equals("tell") ? "what you told it" : !file ? "the page" : target.isBlank() || target.startsWith("-") ? "the file" : Path.of(target).getFileName().toString();
                System.out.println("\n" + u.said(what) + (file ? " It did not mark the file as read." : "") + "\n" + u.todo());
                return 1;
            }
            finally {
                // a read that ended without its last step (a Geni sign-in that ran out) still asks what waits; an error stops the asker at once
                if (ended) endAsking(); else FamilyAsking.stop(asking);
            }
        }
        if (args[2].equals("split")) {
            // split "<name>" --as "<name (what tells them apart)>" --claims F-0003,F-0007
            String name = "", as = ""; List<String> claims = new ArrayList<>();
            for (int i = 3; i < args.length; i++) {
                if (args[i].equals("--as") && i + 1 < args.length) as = args[++i];
                else if (args[i].equals("--claims") && i + 1 < args.length) for (String c : args[++i].split("[,\\s]+")) { if (!c.isBlank()) claims.add(c.strip()); }
                else name = name.isEmpty() ? args[i] : name + " " + args[i];
            }
            if (name.isEmpty() || as.isEmpty() || claims.isEmpty()) { System.err.println("usage: researchzosho genealogy split \"<name>\" --as \"<name (born 1851)>\" --claims F-0003,F-0007\n  two people share the name: the claims you list move to the second one. `researchzosho map \"<name>\"` shows the claims with their ids"); return 2; }
            try {
                name = named(store, name);
                if (name == null) return 1;
                var o = FamilySplit.split(store, name, as, claims);
                System.out.println(o.moved() + " claim(s) now about " + as + (o.notFound().isEmpty() ? "" : "\n  no such claim: " + String.join(", ", o.notFound())) + (o.notAbout().isEmpty() ? "" : "\n  not about " + name + ": " + String.join(", ", o.notAbout())));
                return o.moved() > 0 ? 0 : 1;
            } catch (IllegalArgumentException e) { System.err.println(e.getMessage()); return 2; }
        }
        if (args[2].equals("link")) return FamilyLinks.cli(store, System.out, Arrays.asList(args).contains("--open"), Arrays.asList(args).contains("--choices"), linkModel());
        if (args[2].equals("who") && Arrays.asList(args).contains("--answered")) return FamilyNameQuestions.cliAnswered(store, System.out);
        if (args[2].equals("who")) return who(store, args);
        if (args[2].equals("names")) {
            if (args.length < 4) { System.err.println("usage: researchzosho genealogy names <person>\n  a person's names over their life, each with how it came (at birth, by marriage, by adoption, as heir) and the years it was used, and the families they belonged to. The name you give is the person's name as your library writes it."); return 2; }
            String who = named(store, String.join(" ", Arrays.asList(args).subList(3, args.length)));
            if (who == null) return 1;
            return FamilyNamePages.cliNames(store, who, System.out);
        }
        if (args[2].equals("family")) return FamilyNamePages.cliFamily(store, String.join(" ", Arrays.asList(args).subList(3, args.length)).strip(), System.out);
        if (args[2].equals("summary")) return FamilySummary.cli(store, Arrays.asList(args).subList(3, args.length), System.out);
        if (args[2].equals("transcript")) return transcript(store, args);
        if (args[2].equals("hold")) return hold(store, args);
        if (args[2].equals("research")) return research(store, args);
        if (args[2].equals("life")) {
            if (args.length < 4) { System.err.println("usage: researchzosho genealogy life <person> [--with <other person>]\n  what the library holds of a person's life, in order of date: each line a claim, with how far it has come and where it is from"); return 2; }
            List<String> rest = new ArrayList<>(Arrays.asList(args).subList(3, args.length));
            String with = ""; int w = rest.indexOf("--with");
            if (w >= 0) { with = String.join(" ", rest.subList(w + 1, rest.size())); rest = rest.subList(0, w); }
            String who;
            if (with.isBlank()) { who = named(store, String.join(" ", rest)); if (who == null) return 1; }
            else { String[] two = namedPair(store, String.join(" ", rest), with); if (two == null) return 1; who = two[0]; with = two[1]; }
            var lines = FamilyLife.of(store, who);
            if (lines == null) { System.err.println("There is nobody named \"" + who + "\" in your library." + gone(store, who) + " The command researchzosho genealogy tree with a name draws that person's family, with the names written as the library writes them."); return 1; }
            if (!with.isBlank()) {
                // two people side by side, year by year: the way to tell two people of one name apart
                var other = FamilyLife.of(store, with);
                if (other == null) { System.err.println("There is nobody named \"" + with + "\" in your library." + gone(store, with)); return 1; }
                System.out.println("The two lives side by side, year by year. A year with a birth or a death on both sides is the one to look at: it tells two people of one name apart, or shows a fact filed under the wrong one.\n");
                System.out.print(FamilyLife.beside(FamilyLife.heading(store, who), lines, FamilyLife.heading(store, with), other));
                return 0;
            }
            System.out.print(FamilyLife.render(who, FamilyLife.heading(store, who), lines));
            System.out.print(FamilyLife.otherNames(store, who));
            return 0;
        }
        if (args[2].equals("log")) {
            // log <person>: every search the runs made for this person, with the date, where, the exact words and what came back
            if (args.length < 4) { System.err.println("usage: researchzosho genealogy log <person>\n  the research log for one person: every search made for them, with the date, where it looked, the exact words, the years and what came back."); return 2; }
            String who = String.join(" ", Arrays.asList(args).subList(3, args.length));
            FamilyQuestions.Found found = FamilyQuestions.find(store, who);
            if (found.ambiguous()) { sayAmbiguous(who, found); return 1; }
            if (!found.found()) { System.err.println("There is nobody named \"" + who + "\" in your library." + gone(store, who) + (found.could().isEmpty() ? "" : " It could be: " + String.join(", ", found.could().stream().limit(8).toList()) + ".")); return 1; }
            if (!found.note().isEmpty()) System.out.println(found.note() + "\n");
            List<String> meant = List.of(found.person());
            List<SearchLog.Entry> made = SearchLog.about(store, meant.get(0) + ":");
            List<Looked.Entry> empty = Looked.about(store, meant.get(0) + ":", 50);
            // the person under their names over the life: the heading in the sentences, and which name each search carried
            Graph view = FamilyPeople.view(store);
            String pid = view.nodeIdOf(meant.get(0));
            String heading = view.node(pid) == null ? meant.get(0) : FamilyNameHistory.of(view).heading(pid);
            List<FamilyQuestions.Period> periods = FamilyQuestions.periods(view, pid);
            if (made.isEmpty()) System.out.println("No research run has searched for " + heading + " yet. The command researchzosho genealogy research \"" + meant.get(0) + "\" starts one.");
            else {
                System.out.println("Every search the library made for " + heading + ", newest first. Each line: the date, where it looked, the exact words, the years, and how many results came back. \"did not answer\" means the collection could not be reached that day, so that search still has to be made; the next search for this person is told to make it again."
                        + (periods.size() > 1 ? " This person carried more than one name, so each line ends with the name the search used and the years the person carried that name, or with [both names] when it used both, which is how the record of a change of name is found." : "") + "\n");
                for (SearchLog.Entry e : made) System.out.println("  " + SearchLog.line(e) + FamilyQuestions.carriedName(periods, e.query()));
            }
            // a search written down by hand with looked add counts as much as a run's: the next run is told of both
            if (!empty.isEmpty()) { System.out.println("\nSearches that found nothing, made by a research run or by you and written down with researchzosho looked add:"); for (Looked.Entry e : empty) System.out.println("  " + Looked.line(e) + FamilyQuestions.carriedName(periods, e.what())); }
            printSiteLinks(store, meant.get(0));
            return 0;
        }
        if (args[2].equals("reset")) {
            boolean yes = Arrays.asList(args).contains("--yes"), everything = Arrays.asList(args).contains("--all");
            String only = ""; for (int i = 3; i + 1 < args.length; i++) if (args[i].equals("--from")) only = args[i + 1];
            if (everything && !only.isBlank()) { System.err.println("--all and --from do not go together. --all takes out everything the library read for your family, and --from takes out what it read from one file or page. Give the command again with only one of them."); return 2; }
            if (only.isBlank()) return everything ? resetAll(store, yes) : resetFolder(store, yes);
            var plan = FamilyReset.plan(store, ownWriters(), only);
            boolean one = plan.files().size() <= 1;
            String it = one ? "it" : "them";
            // what changes is decided first: with nothing to take out, the command says so and lists nothing it will not do
            if (plan.claims().isEmpty() && plan.thinned().isEmpty() && plan.files().isEmpty() && plan.leftOver().isEmpty()) {
                System.out.println(plan.files().isEmpty() ? "There is nothing to take out: the library has read no file or page whose name or address has \"" + only + "\" in it. Check the words after --from."
                        : "There is nothing to take out from " + (one ? plan.files().get(0) : "these files and pages: " + String.join(", ", plan.files())) + ". Every fact the library holds from " + it + " is one you checked.");
                return 0;
            }
            // the words match no file or page: what a read that was stopped or taken back left in the list of people is all there is to take out
            if (plan.files().isEmpty()) {
                System.out.print("The library has read no file or page whose name or address has \"" + only + "\" in it, so there is no fact to take out.\n\nIt will take out:\n" + leftOverSaid(plan)
                        + "\nIt keeps:\n  Everything else in your library.\n");
                System.out.println(copySaid(store));
                if (!resetAnswered(yes)) return 0;
                var did = FamilyReset.apply(store, plan);
                System.out.println("\n" + doneSaid(did) + "\nThe copy is saved in " + did.backup().toAbsolutePath().normalize());
                return 0;
            }
            // a web page or a Geni profile is read again by its address, not by a read of the folder
            List<String> addresses = plan.files().stream().filter(x -> x.matches("(?i)https?://.+")).toList();
            List<FamilyReads.Reread> readAgain = new ArrayList<>();
            // a list of web addresses is read again as it was read, with its folder or by itself, and not page by page
            List<String> lists = FamilyReset.listsNamed(store, only);
            Set<String> listPages = FamilyReset.pagesOf(store, lists);
            FamilyReads.Index reads = FamilyReads.Index.of(store);
            for (String l : lists) { FamilyReads.Reread r = reads.readOf(l); if (!readAgain.contains(r)) readAgain.add(r); }
            for (String a : addresses) if (!listPages.contains(FamilyReads.addressKey(a)))
                readAgain.add(GeniFamily.guidOf(a) != null ? new FamilyReads.Reread(FamilyReads.GENI, a, 1, "", "") : new FamilyReads.Reread(FamilyReads.PAGE, a, 0, "", ""));
            StringBuilder b = new StringBuilder();
            if (!lists.isEmpty()) {
                List<String> led = plan.files().stream().filter(x -> !lists.contains(x)).toList();
                b.append(lists.size() == 1 ? lists.get(0) + " is a list of web addresses" : String.join(" and ", lists) + " are lists of web addresses")
                  .append(". This takes out what the library read from the pages and Geni profiles ").append(lists.size() == 1 ? "it" : "they").append(" led to:\n")
                  .append(String.join("\n", led.stream().map(x -> "    " + x).toList())).append("\n\n");
            }
            else if (one) b.append("This takes out what the library read from one file or page: ").append(plan.files().isEmpty() ? "the one whose name has \"" + only + "\" in it" : plan.files().get(0)).append(".\n\n");
            else b.append("The words \"").append(only).append("\" are in the names of ").append(plan.files().size()).append(" files or pages. This takes out what the library read from each of them:\n")
                  .append(String.join("\n", plan.files().stream().map(x -> "    " + x).toList()))
                  .append("\nTo take out only one of them, give its whole path or address after --from, as it is written here.\n\n");
            b.append("It will take out:\n");
            if (!plan.claims().isEmpty()) {
                b.append("  ").append(factsN(plan.claims().size())).append(" from ").append(it).append(" that you have not checked yet.\n");
                b.append("  The people, places, events and names that only those facts mention.\n");
            }
            b.append(waitingSaid(plan.waiting()));
            b.append(leftOverSaid(plan));
            int files = plan.files().size() - addresses.size();
            if (files > 0) b.append("  The note that ").append(files == 1 ? "this file was" : "these files were").append(" read, so the next read of your folder reads ").append(files == 1 ? "it" : "them").append(" again.\n");
            if (!addresses.isEmpty()) b.append("  The note that ").append(addresses.size() == 1 ? "this page was" : "these pages were").append(" read. The command that reads ")
                    .append(addresses.size() == 1 ? "it" : "them").append(" again is printed when the reset is done.\n");
            b.append("\nIt keeps:\n");
            int thinned = plan.thinned().size();
            if (thinned > 0) b.append("  ").append(factsN(thinned)).append(" from ").append(it).append(" that another file or a research run also gives. ").append(thinned == 1 ? "It stays" : "They stay")
                    .append(", with that other source. Reading ").append(one ? "this file" : "these files").append(" again adds ").append(it).append(" back as a source. A fact the library itself marked as wrong stays marked.\n");
            if (plan.kept() > 0) b.append("  ").append(factsN(plan.kept())).append(" from ").append(it).append(" that you checked or moved to another person, or that the library itself marked as wrong.\n");
            if (plan.replaced() > 0) b.append("  ").append(plan.replaced() == 1 ? "1 older fact" : plan.replaced() + " older facts").append(" from ").append(it).append(" that a newer copy of the file replaced. ")
                    .append(plan.replaced() == 1 ? "It stays" : "They stay").append(" on record.\n");
            b.append("  Everything else in your library.\n");
            System.out.print(b);
            System.out.println(copySaid(store));
            if (!resetAnswered(yes)) return 0;
            var did = FamilyReset.apply(store, plan);
            System.out.println("\n" + doneSaid(did) + (did.thinned() == 0 ? "" : " " + factsN(did.thinned()) + " that another source also gives " + (did.thinned() == 1 ? "stays" : "stay") + ", without " + (one ? "this file" : "these files") + " as a source.")
                    + "\nThe copy is saved in " + did.backup().toAbsolutePath().normalize()
                    + (readAgain.isEmpty() ? "" : "\n\nTo read " + (!lists.isEmpty() ? (lists.size() == 1 ? "the list" : "the lists") : readAgain.size() == 1 ? "this page" : "these pages") + " again, give:\n" + FamilyReads.commands(readAgain, "    ").stripTrailing()));
            return 0;
        }
        if (args[2].equals("tidy")) {
            boolean yes = Arrays.asList(args).contains("--yes");
            Set<Integer> leave = new HashSet<>();
            for (int i = 3; i + 1 < args.length; i++) if (args[i].equals("--apart")) for (String n : args[i + 1].split("[,\\s]+")) if (n.matches("\\d+")) leave.add(Integer.parseInt(n));
            // the names as the entries write them, before the evidence links them: what tidy offers is the person's own join
            var g = FamilyPeople.unlinkedView(store);
            Map<String, Finding> findings = new LinkedHashMap<>();
            for (Finding f : store.scanFindings().findings()) findings.put(f.id(), f);
            var pairs = FamilyNames.sameByName(g, Graph.differentPairs(store));
            // each pair laid out with what else agrees; a pair whose facts do not fit one person is left for the family to decide on its own
            List<String[]> join = new ArrayList<>(), held = new ArrayList<>();
            List<FamilySame.Comparison> joinWhy = new ArrayList<>(), heldWhy = new ArrayList<>();
            for (String[] pr : pairs) {
                FamilySame.Comparison c = FamilySame.compare(g, findings, g.nodeIdOf(pr[0]), g.nodeIdOf(pr[1]));
                if (c.twoPeople() || !c.differ().isEmpty()) { held.add(pr); heldWhy.add(c); } else { join.add(pr); joinWhy.add(c); }
            }
            List<String[]> badNames = new ArrayList<>();
            for (var n : g.nodes()) if (n.kind().equals("person")) for (String a : n.aliases()) if (!FamilyNames.keepAsOtherName(n.label(), a)) badNames.add(new String[]{n.label(), a});
            // one name written two ways in the list of names, and no fact about one of the two entries: a name given one way could miss the person
            List<FamilyNames.Twin> twins = FamilyNames.twins(g, Graph.differentPairs(store));
            System.out.println("The library looked for people who are in your family twice because their name was written in two ways, for example \"Hisa Endō\" on a web page and \"Endo, Hisa\" in a book's index. "
                    + "It only counts two names as one person when the words of the name are the same, apart from their order, accents and commas. A middle name, Jr. or Sr., or a note in brackets keeps two people apart. "
                    + "Under each pair it says what else is the same for both: a parent, a husband or wife, a place or a year.\n");
            if (join.isEmpty() && held.isEmpty() && badNames.isEmpty() && twins.isEmpty()) { System.out.println("It found nothing to tidy."); return 0; }
            if (!join.isEmpty()) {
                System.out.println(join.size() + (join.size() == 1 ? " person is" : " people are") + " in twice. The second name would become another name of the first, and all the facts would be about the one person:\n");
                for (int i = 0; i < join.size(); i++) System.out.println("  " + (i + 1) + ". " + join.get(i)[0] + "   ←   " + join.get(i)[1] + "\n       " + joinWhy.get(i).said());
            }
            if (!twins.isEmpty()) {
                System.out.println((join.isEmpty() ? "" : "\n") + twins.size() + (twins.size() == 1 ? " name is" : " names are") + " in your library's list of names twice, written two ways, for example with and without a space, and no fact is about one of the two entries. "
                        + "The empty entry would become another name of the other one, so that both ways of writing the name lead to the same person. Nothing is lost:\n");
                for (int i = 0; i < twins.size(); i++) {
                    FamilyNames.Twin t = twins.get(i);
                    System.out.println("  " + (join.size() + i + 1) + ". " + t.keep() + "   ←   " + t.empty() + "\n       No fact is about \"" + t.empty() + "\", and " + FamilyQuestions.factsSaid(t.keepFacts()) + " about \"" + t.keep() + "\".");
                }
            }
            if (!held.isEmpty()) {
                System.out.println("\n" + held.size() + (held.size() == 1 ? " pair has" : " pairs have") + " a name written the same way, but something in the facts does not fit one person. These are not joined. Decide each one yourself:\n");
                for (int i = 0; i < held.size(); i++) {
                    FamilySame.Comparison c = heldWhy.get(i);
                    System.out.println("  " + held.get(i)[0] + "   and   " + held.get(i)[1] + "\n       " + (c.twoPeople() ? "The facts say they are two people: " + c.related().text() + "." : c.said())
                            + "\n       If they are one person:  researchzosho graph merge \"" + held.get(i)[1] + "\" \"" + held.get(i)[0] + "\" --because \"<what shows they are one>\""
                            + "\n       If they are two people:  researchzosho genealogy different \"" + held.get(i)[0] + "\" \"" + held.get(i)[1] + "\" --because \"<what shows they are two>\"");
                }
            }
            if (!badNames.isEmpty()) {
                System.out.println("\n" + badNames.size() + " \"other names\" are a single word, with a title or without (Mr. Hart), or a part of the person's own name. Strangers share such a word, which makes them look like the same person. They would be removed:\n");
                for (String[] b : badNames.stream().limit(40).toList()) System.out.println("  " + b[0] + ":  " + b[1]);
                if (badNames.size() > 40) System.out.println("  … and " + (badNames.size() - 40) + " more");
            }
            if (join.isEmpty() && badNames.isEmpty() && twins.isEmpty()) return 0;
            if (!yes) {
                boolean atKeyboard = Interaction.interactive();
                String answer = ask(System.lineSeparator() + "Type yes and press Enter to join the numbered pairs" + (badNames.isEmpty() ? "" : " and remove the other names") + ". "
                        + (join.isEmpty() && twins.isEmpty() ? "" : "To leave some pairs apart, type their numbers instead, for example 2,5: the other pairs are joined, and the ones you typed are written down as two different people, so they are not shown again. ")
                        + "Or just press Enter to change nothing: ");
                String a = answer.strip();
                if (a.matches("[\\d,\\s]+")) { for (String n : a.split("[,\\s]+")) if (!n.isBlank()) leave.add(Integer.parseInt(n)); }
                else if (!a.equalsIgnoreCase("yes")) { System.out.println("\nNothing was changed." + (!atKeyboard ? " To go ahead without being asked, add --yes at the end of the command. To leave some pairs apart, add --apart and their numbers, for example --apart 2,5." : "")); return 0; }
            }
            int joined = 0, apart = 0;
            String firstFolded = null;
            for (int i = 0; i < join.size(); i++) {
                String[] pr = join.get(i);
                if (leave.contains(i + 1)) { Graph.different(store, pr[0], pr[1], "genealogy tidy", "left apart in genealogy tidy"); apart++; continue; }
                String why = "the same words of the name, written another way" + (joinWhy.get(i).reason().isEmpty() ? "" : "; " + joinWhy.get(i).reason());
                Graph.merge(store, pr[1], pr[0], "genealogy tidy", why, this);
                if (firstFolded == null) firstFolded = pr[1];
                joined++;
            }
            for (int i = 0; i < twins.size(); i++) {
                FamilyNames.Twin t = twins.get(i);
                // kept apart: written down as two people, as the question says, so that the pair is not shown again
                if (leave.contains(join.size() + i + 1)) { Graph.different(store, t.emptyName(), t.keepName(), "genealogy tidy", "left apart in genealogy tidy"); apart++; continue; }
                Graph.merge(store, t.emptyName(), t.keepName(), "genealogy tidy", "one name written two ways; no fact was about \"" + t.empty() + "\"", this);
                if (firstFolded == null) firstFolded = t.emptyName();
                joined++;
            }
            int removed = Graph.dropAliases(store, badNames);
            System.out.println("\nDone. " + joined + (joined == 1 ? " person was" : " people were") + " joined, " + (apart == 0 ? "" : apart + (apart == 1 ? " pair was" : " pairs were") + " written down as two people, ")
                    + "and " + removed + " other names were removed. "
                    + (firstFolded == null ? "" : "Each joining is written down in the library's list of changes with the reason. To take one back, give the command researchzosho graph unmerge with the second name of the pair, for example researchzosho graph unmerge \"" + firstFolded + "\". ")
                    + "The command researchzosho genealogy check shows what is left to look at.");
            return 0;
        }
        if (args[2].equals("check")) {
            if (args.length == 4 && (args[3].equals("accept") || args[3].equals("reopen"))) { System.err.println("usage: researchzosho genealogy check accept <code> \"<why it is right as it stands>\"   or   researchzosho genealogy check reopen <code>\n  The code is in square brackets after each thing the check lists."); return 2; }
            if (args.length >= 5 && args[3].equals("accept")) {
                String why = args.length > 5 ? String.join(" ", Arrays.asList(args).subList(5, args.length)) : "";
                if (why.isBlank()) { System.err.println("usage: researchzosho genealogy check accept <code> \"<why it is right as it stands>\"\n  The code is in square brackets after each thing the check lists. The words after it say why the thing is right, for whoever reads the check later."); return 2; }
                try {
                    FamilyChecks.Problem p = FamilyChecks.accept(store, args[4], why, System.getProperty("user.name", ""));
                    System.out.println("Written down. The check lists this apart, under things already looked at, until the facts it is about change:\n  " + p.text() + "\nTo put it back on the list: researchzosho genealogy check reopen " + p.id());
                    return 0;
                } catch (IllegalArgumentException e) { System.err.println(e.getMessage()); return 1; }
            }
            if (args.length >= 5 && args[3].equals("reopen")) {
                boolean was = FamilyChecks.reopen(store, args[4]);
                System.out.println(was ? "It is back on the list: researchzosho genealogy check shows it with the other things to look at." : "Nothing with the code " + args[4] + " was written down as looked at. The codes of the things already looked at are at the end of researchzosho genealogy check.");
                return was ? 0 : 1;
            }
            System.out.print(FamilyChecks.forPerson(store, FamilyChecks.check(store)));
            return 0;
        }
        if (args[2].equals("source")) {
            if (args.length < 4) { System.err.println("usage: researchzosho genealogy source <file name or web address>\n  every fact in your library that rests on one source, for example a book you now doubt, and which of them have no other source"); return 2; }
            String what = String.join(" ", Arrays.copyOfRange(args, 3, args.length)).strip();
            String said = Evidence.restingForPerson(what, Evidence.restingOn(store, what, "", this), false);
            System.out.print(said.isEmpty() ? "No fact in your library has " + what + " as its source. Give the file's name as it is in your folder (for example community.pdf) or the page's whole web address.\n" : said);
            return 0;
        }
        if (args[2].equals("different")) {
            List<String> names = new ArrayList<>(); String because = ""; boolean undo = false;
            for (int i = 3; i < args.length; i++) { if (args[i].equals("--because") && i + 1 < args.length) because = args[++i]; else if (args[i].equals("--undo")) undo = true; else names.add(args[i]); }
            if (names.size() != 2) { System.err.println("usage: researchzosho genealogy different \"<name>\" \"<other name>\" --because \"<what shows they are two people>\"\n  Writes down that two people in your library are two different people, so the check and genealogy tidy do not ask about them again.\n  Add --undo to take that back."); return 2; }
            String[] two = namedPair(store, names.get(0), names.get(1));
            if (two == null) return 1;
            names = List.of(two[0], two[1]);
            if (undo) {
                boolean was = Graph.notDifferent(store, names.get(0), names.get(1), "person");
                System.out.println(was ? "Taken back. The check may list " + names.get(0) + " and " + names.get(1) + " as one person written two ways again." : names.get(0) + " and " + names.get(1) + " were not written down as two people.");
                return was ? 0 : 1;
            }
            if (because.isBlank()) { System.err.println("Say what shows they are two people, after --because. For example: --because \"different fathers in the register\". The reason is kept with the answer."); return 2; }
            try { Graph.different(store, names.get(0), names.get(1), "person", because); }
            catch (IllegalArgumentException e) { System.err.println(e.getMessage()); return 1; }
            System.out.println("Written down: " + names.get(0) + " and " + names.get(1) + " are two different people. The check and genealogy tidy will not put them forward as one again. To take it back, give the same command with --undo at the end.");
            return 0;
        }
        if (args[2].equals("related")) {
            List<String> names = new ArrayList<>(); for (int i = 3; i < args.length; i++) names.add(args[i]);
            if (names.size() != 2) { System.err.println("usage: researchzosho genealogy related \"<name>\" \"<other name>\"\n  How two people in your library are related, worked out from the claims about parents and marriages."); return 2; }
            String[] two = namedPair(store, names.get(0), names.get(1));
            if (two == null) return 1;
            names = List.of(two[0], two[1]);
            var g = FamilyPeople.view(store);
            String a = g.nodeIdOf(names.get(0)), b = g.nodeIdOf(names.get(1));
            for (String[] n : new String[][]{{names.get(0), a}, {names.get(1), b}}) if (g.node(n[1]) == null) {
                System.err.println(g.curated().resolve(n[0]) != null ? "No fact in your library is about \"" + n[0] + "\" yet, so the library cannot say how " + n[0] + " is related to anybody."
                        : "Nobody in your library is called \"" + n[0] + "\". researchzosho map \"" + n[0] + "\" shows the names close to it.");
                return 1;
            }
            String said = FamilyKin.said(g, a, b);
            System.out.println(said != null ? said : "The library finds no line between " + g.node(a).label() + " and " + g.node(b).label() + " in the claims about parents and marriages. They may be related through somebody whose parents are not in your library yet.");
            return said != null ? 0 : 1;
        }
        if (args[2].equals("tree")) {
            List<String> name = new ArrayList<>(); String out = "";
            for (int i = 3; i < args.length; i++) { if (args[i].equals("--out") && i + 1 < args.length) out = args[++i]; else if (!args[i].equals("--include-living")) name.add(args[i]); }
            if (name.isEmpty()) { System.err.println("usage: researchzosho genealogy tree <person> [--out tree.svg]"); return 2; }
            String focus = named(store, String.join(" ", name));
            if (focus == null) return 1;
            var tree = FamilyTree.around(store, focus, 5, 4);
            if (tree.focus() == null) { System.err.println("Nobody of that name is in your library's family." + gone(store, focus) + " The command researchzosho map \"" + String.join(" ", name) + "\" shows the names your library holds that are close to it."); return 1; }
            Path file = Path.of(out.isBlank() ? "family-tree.svg" : out);
            Files.writeString(file, FamilyTree.svg(tree, n -> "", f -> ""), StandardCharsets.UTF_8);
            System.out.println("The drawing is saved as " + file.toAbsolutePath() + ". Open this file in your web browser to see it.\n"
                    + "It shows " + tree.people().size() + (tree.people().size() == 1 ? " person" : " people") + " around " + tree.focus().heading() + ", whose box has a red border. Older generations are above, younger ones below. A double line is a marriage, and a dashed line is an adoption, a step-parent or a foster parent.\n"
                    + "A grey line is a fact nobody has checked yet, a green line is a fact you accepted, and a line with red dots is a fact somebody disputes.\n"
                    + "To draw the family around somebody else, give the same command with that person's name.");
            return 0;
        }
        switch (args[2]) {
            case "import" -> {
                if (args.length < 4) { System.err.println("usage: researchzosho genealogy import <file.ged> [--dry]"); return 2; }
                if (Arrays.asList(args).contains("--dry")) { System.out.print(Gedcom.dryRun(store, Path.of(args[3]))); return 0; }
                // filed under the one lock an answer typed beside the reading is filed under
                Set<String> before;
                Gedcom.Outcome o;
                FamilyAsking.FILING.lock();
                try {
                    before = askBefore(store);
                    o = Gedcom.importFile(store, Path.of(args[3]));
                } finally { FamilyAsking.FILING.unlock(); }
                if (!inFolder) FamilyReads.readFile(store, Path.of(args[3]), FamilyReads.TREE, "", "", "");   // a tree file of a folder is written down by the folder read
                String fileName = Path.of(args[3]).getFileName().toString();
                System.out.println("The library read " + o.persons() + (o.persons() == 1 ? " person" : " people") + " and " + o.families() + (o.families() == 1 ? " family" : " families") + " from " + fileName
                        + ", and wrote down " + o.findings() + (o.findings() == 1 ? " fact" : " facts") + " about them as drafts: facts nobody has checked yet, which wait for you in researchzosho inbox."
                        + (o.mayBeLiving() == 0 ? "" : " " + o.mayBeLiving() + (o.mayBeLiving() == 1 ? " person" : " people") + " in the file may still be living, by the dates: a search asks about their work and public life, not about their death.")
                        + (o.names() == 0 && o.houses() == 0 ? "" : "\nIt wrote down " + (o.names() == 0 ? "" : o.names() + (o.names() == 1 ? " name" : " names") + " that people carried in their lives, such as a name at birth or a name taken on marriage or adoption")
                                + (o.names() > 0 && o.houses() > 0 ? ", and " : "") + (o.houses() == 0 ? "" : o.houses() + (o.houses() == 1 ? " family" : " families") + " that the file describes as a family, such as a house or a 家, with " + o.members() + (o.members() == 1 ? " membership" : " memberships"))
                                + ". To see the names of one person and the families they belonged to, give the command researchzosho genealogy names followed by the person's name in quotation marks.")
                        + (o.superseded() == 0 ? "" : "\n" + o.superseded() + (o.superseded() == 1 ? " draft from an earlier copy of this file was" : " drafts from an earlier copy of this file were") + " replaced, because this copy of the file says something else about the same birth, baptism, marriage, death or burial, or no longer says who a family's children are in the same way. The earlier " + (o.superseded() == 1 ? "one is" : "ones are") + " kept, marked as replaced.")
                        + (o.moved() == 0 ? "" : "\n" + o.moved() + (o.moved() == 1 ? " fact from an earlier import of this file is" : " facts from an earlier import of this file are") + " filed now under the person the file names apart, such as a father and a son of one name. The earlier " + (o.moved() == 1 ? "draft is" : "drafts are") + " kept, marked as replaced.")
                        + (o.apart().isEmpty() ? "" : "\n  " + String.join("\n  ", o.apart()))
                        + (o.problems().isEmpty() ? "" : "\n  " + String.join("\n  ", o.problems())));
                FamilyAsking.filing(() -> { linkAgain(store); return null; });   // a tree file often holds the record that settles who a person written only by a family name is
                // a tree file of a folder hands its questions to the folder's asker; on its own, the questions are asked now, and what waits is said
                try { askAfterFiling(store, Set.of(), before, !inFolder); }
                finally { if (!inFolder) endAsking(); }
                return 0;
            }
            case "export" -> {
                if (args.length < 4) { System.err.println("usage: researchzosho genealogy export <person>"); return 2; }
                FamilyQuestions.Found found = FamilyQuestions.find(store, args[3]);
                if (!found.note().isEmpty()) System.err.println(found.note());   // the file goes to the output, the sentence beside it
                System.out.print(Gedcom.export(store, found.found() ? found.person() : args[3]));
                return 0;
            }
            default -> { System.err.println("usage: researchzosho genealogy import <file.ged> | export <person>"); return 2; }
        }
    }
}
