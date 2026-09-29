package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;

/**
 * A profile is data plus a small adapter on top of the general core — never a fork of it. It declares
 * the predicates and node kinds its field uses, how to import and export that field's interchange
 * format, the register the cataloger reads when it works material of that kind, and any rule its
 * research must follow (what to ask of a person who may be living, say). The core suite runs with no profile enabled and
 * every core feature still works; a profile can add, never change.
 *
 * <p>Every profile is on until the library says otherwise: {@code profiles: science} in
 * {@code catalog/library.md} keeps only the ones it names. Being on costs nothing. Most profiles' rules reach a research run when the
 * question is one of their field's ({@link #applies}). A profile that {@link #joinsOnlyWhenAsked joins only when asked} is a module: on
 * means available, and it acts only on its own work, which {@link Fields} tells by where the work came from. An ordinary library, ordinary
 * questions and ordinary claims look exactly as they would with it switched off; a question that looks like one of its own is told that
 * the field is there ({@link #suggests}).
 */
public interface Profile {

    String name();

    String description();

    /** The predicates this profile declares, seeded into the graph's vocabulary when the profile is enabled. */
    List<Vocabulary.Term> predicates();

    /** Node kinds beyond the core's seven that this profile uses ("family" for a GEDCOM family record, say). */
    default List<String> nodeKinds() { return List.of(); }

    /** A paragraph every research worker reads when the question is one of this field's. */
    default String register() { return ""; }

    /**
     * Whether a question belongs to this field, judged from its words alone. For a profile that joins only when asked, the words never
     * switch it on: they only let the person be told it is there ({@link #suggests}).
     */
    default boolean applies(String question) { return false; }

    /**
     * Whether this field joins a run only when somebody asks for it: the research command's {@code --<name>}, the web page's box,
     * {@code field} in the research tool, a yes to the chat's question, or the field's own commands. Its words never switch it on, its
     * relations read only its own claims, and an ordinary library looks as if it were off.
     */
    default boolean joinsOnlyWhenAsked() { return false; }

    /** For a field that joins only when asked: whether a question looks like one of its own, so the person can be told the field is there. */
    default boolean suggests(LibraryStore store, String question) { return suggests(question); }

    /** The same from the words alone. */
    default boolean suggests(String question) { return applies(question); }

    /** One plain sentence for the person: what this field's mode does differently. Said where a question looks like one of the field's. */
    default String offer() { return ""; }

    /** The words beside the web research page's box that asks for this field. */
    default String choice() { return ""; }

    /** The folder this field's own intake keeps its files in, or null: when it exists the library holds the field's work. */
    default Path workFolder(LibraryStore store) { return null; }

    /** A sentence for the model that writes a claim's triple, when the claim is this field's work: the words for the field's relations. */
    default String extractionRule() { return ""; }

    /**
     * What this field adds to what a run of it is told the library already holds, after the search log of its subject ({@code
     * searched}); "" for nothing. Asked only for a run of this field.
     */
    default String knownBlock(LibraryStore store, String question, List<SearchLog.Entry> searched) { return ""; }

    /**
     * The field's own reading of the graph as it is built: who is a person, who may be living. {@code g.wide()} is the field's own view
     * ({@link Graph#build(LibraryStore, Profile)}), where every claim counts; in the core graph only the field's own work does.
     */
    default void onGraph(Graph.Reading g) { }

    /**
     * Whether a name a claim writes is read as the entry of nodes.md that one of the entry's other names leads to: {@code written} is the name
     * as the claim writes it, {@code entry} the entry, whose own name it is not. False keeps the name a node of its own. Asked of a field
     * that joins only when asked, for the names its own work writes, and in the field's own view ({@link Graph#build(LibraryStore, Profile)})
     * for every name; the core reads every other name, which is what true, the default, keeps.
     */
    default boolean readsOtherName(String written, Vocabulary.Term entry) { return true; }

    /**
     * Which entry each name of a claim is in this field's own view, where the field linked it to another entry from the evidence ({@link
     * Graph.Links}). Asked only by the field's own view ({@link Graph#build(LibraryStore, Profile)}), with the claims it is read from; the core
     * graph never asks, so an ordinary claim and the core's reading of the field's own claims stay as they are. Null for none.
     */
    default Graph.Links links(LibraryStore store, List<Finding> claims) throws IOException { return null; }

    /** Whether a run of this field settles what software does from the code of a repository the library holds. */
    default boolean readsCode() { return false; }

    /**
     * What the chat's instructions add when the library holds this field's work (or always, for a field that recognises its own
     * questions): a paragraph on the field's own chat tools. "" for nothing.
     */
    default String chatNote() { return ""; }

    /** The library tools the chat is given for this field, on the same terms as {@link #chatNote}. */
    default List<String> chatTools() { return List.of(); }

    /**
     * For a field that joins only when asked, once per library, whether the field is on or off: what the field once wrote into the
     * library's own files for every claim to read, taken back out. The sentences for the owner; none when nothing was there.
     */
    default List<String> tidy(LibraryStore store) throws IOException { return List.of(); }

    /**
     * For a field that joins only when asked and is on, once per library, when it first runs this version ({@link Fields#migrate}): its
     * earlier work made ready, with {@code recorded} the reports of its runs the upgrade found by where they came from. What it tells the
     * owner.
     */
    default List<String> upgrade(LibraryStore store, Set<String> recorded) throws IOException { return List.of(); }

    /**
     * Whether a research job an older version filed, before jobs carried their field, was filed by this field's own command. Told by the
     * way that command wrote the job, never by the words of a question a person wrote. False by default.
     */
    default boolean filedByOwnCommand(JsonNode args) { return false; }

    /**
     * The person disputed or retired a claim: the open questions this field wrote from it, which would give it as known, to be taken off
     * the list. Asked of every profile the build knows, on or off, so a field switched off still keeps its questions honest; a field that
     * joins only when asked is asked only in a library that holds its work, or about a claim that is its own.
     */
    default List<String> onDecision(LibraryStore store, Finding decided) throws IOException { return List.of(); }

    /**
     * The years a question's subject lived, {born, until}, when the question says (the collections of records are chosen by them); null
     * when this field cannot tell. Asked for a run of this field; the first of the run's fields that tells answers.
     */
    default int[] yearsOf(String question) { return null; }

    /** How many years after the event a claim's sources wrote it down, when this field can tell (a tree file's own entry dates); 0 otherwise. */
    default int writtenLater(Finding f) { return 0; }

    /**
     * One fact whichever way round a claim wrote it, for this field's own relations ("A parent-of B" and "B child-of A"); null when the
     * relation is not one of this field's that has another way round.
     */
    default String factKey(String from, String predicate, String to) { return null; }

    /**
     * A sentence this field adds where the core tells a person what to do next, in a library that holds the field's work: {@code
     * situation} names the place ("unmerge-nothing": a name was never joined; "apart": two names are one node). "" for nothing.
     */
    default String hint(String situation) { return ""; }

    /** What a claim's words were checked against, when this field read them from something other than the source's own text (a picture); "" otherwise. */
    default String checkedAgainst(Finding f) { return ""; }

    /** A sentence for the person who just accepted a claim, when this field has something to say of it (its words came from an unchecked reading); "" otherwise. */
    default String onAccept(LibraryStore store, Finding f) throws IOException { return ""; }

    /** A command of this field's own in the terminal chat ("/family …"): true when the line was one and was done here. */
    default boolean chatCommand(LibraryStore store, String line) { return false; }

    /** The chat's help line for this field's command; "" for none. */
    default String chatCommandHelp() { return ""; }

    /**
     * A page of this field's own, at its own address: the title and the body, drawn in the library's frame ({@code wide}: the whole
     * window), or {@code redirect}, where to go after a form was sent.
     */
    record Page(String title, String body, boolean wide, String redirect) { }

    /** The addresses of this field's own pages ("/tree"); they are reachable in any library where the field is on. */
    default List<String> pagePaths() { return List.of(); }

    /**
     * One of this field's own pages, GET ({@code form} empty) or POST. Null for an address that is not one of its. A person who may not
     * see it is refused with a {@link ProtocolError}, as on every page.
     */
    default Page page(LibraryStore store, Patrons.Patron patron, String path, String method, Map<String, String> query, Map<String, String> form) throws IOException { return null; }

    /** A link in the menu of every page, shown in a library that holds this field's work; {@code writersOnly}: only for those who may write. */
    record Link(String href, String label, boolean writersOnly) { }

    default List<Link> menu() { return List.of(); }

    /** A section of the home page, in a library that holds this field's work; "" for none. {@code writer}: whether the reader may write. */
    default String homeSection(LibraryStore store, boolean writer) throws IOException { return ""; }

    /** A note at the top of the inbox, in a library that holds this field's work; "" for none. */
    default String inboxNote(LibraryStore store, boolean writer) throws IOException { return ""; }

    /**
     * One of this field's own library tools ({@code library_who}), called through the protocol, the MCP server and the chat; null for a
     * tool that is not this field's.
     */
    default ObjectNode tool(String name, LibraryProtocol protocol, JsonNode args) throws IOException { return null; }

    /** Whether an open question filed by an older version, without a field mark, is this field's by the words its bracket carries. */
    default boolean ownsLine(String kind) { return false; }

    /** Whether this field's questions are answered from collections of records, so its runs are offered {@code record_search}. */
    default boolean wantsRecords() { return false; }

    /** The writers whose claims are this field's own work (a family account, a GEDCOM import); see {@link Fields#ofClaim}. */
    default List<String> ownWriters() { return List.of(); }

    /**
     * Whether this field's own claims are filed under subjects from the library's subject list. A field whose claims are organised
     * another way (a family's, by person and relation) says no, and the cataloger leaves them alone; everything else is filed as before.
     */
    default boolean filesUnderSubjects() { return true; }

    /** How this field splits a question into sub-questions; added to the planner's instruction. */
    default String planRules() { return ""; }

    /** What this field's reviewer checks before calling the evidence enough; added to the critic's instruction. */
    default String criticRules() { return ""; }

    /** The sections and the care this field's write-up needs; added to the writer's instruction. */
    default String writerRules() { return ""; }

    /** Extra command-line verbs: {@code researchzosho <name> <verb…>}. Return null when the verb is unknown. */
    default Integer cli(LibraryStore store, String[] args) throws Exception { return null; }

    /** One of this field's own commands, for the command's help to show as an example ("genealogy import tree.ged"); "" for none. */
    default String example() { return ""; }

    /** One-line usage for the command's help. */
    default String usage() { return ""; }

    /**
     * Called once when the profile is enabled on a library: seed vocabularies, create files. A field that joins only when asked writes
     * nothing into the library's own vocabulary: its relations are read with its own work, and predicates.md is the owner's.
     */
    default void enable(LibraryStore store) throws IOException {
        if (joinsOnlyWhenAsked()) return;
        for (Vocabulary.Term t : predicates()) Graph.predicate(store, t.slug(), t.description(), t.also());
    }
}
