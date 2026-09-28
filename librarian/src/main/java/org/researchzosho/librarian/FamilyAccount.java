package org.researchzosho.librarian;

import org.researchzosho.drive.DriveClient;
import org.researchzosho.drive.Judge;
import org.researchzosho.drive.aws.Bedrock;
import org.researchzosho.tools.Fetch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Map;
import java.util.Set;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.Arrays;
import java.util.Iterator;
/**
 * What a family already knows, read into the library: a relative's written account, or what the owner tells it. The
 * model reads the text and names the people and the relations, each with the sentence that says it; the harness checks
 * that sentence is in the text, works out the dates, and files one DRAFT claim per relation with the account as its
 * source. Nothing here is verified: a family's account is where the research starts, and a research run then looks
 * for the record that agrees or disagrees.
 */
public final class FamilyAccount {

    private FamilyAccount() { }

    private static final ObjectMapper J = new ObjectMapper();
    static final int CHUNK = 6_000;
    /** How much later than their partner a spouse may have been born, at the outside. */
    static final int SPOUSE_SPAN = 40;
    /** A parent is at least this many years older than their child. */
    static final int PARENT_GAP = 12;

    /**
     * A person as a read names them. {@code family}/{@code given}: the parts of the name as the text gives them, "" when it does not; a
     * name is never split by the position of its words, which differs from language to language.
     */
    public record Person(String name, String reading, List<String> also, String family, String given) {
        public Person {
            also = also == null ? List.of() : also;
            reading = reading == null ? "" : reading;
            family = family == null ? "" : family.strip();
            given = given == null ? "" : given.strip();
        }
        public Person(String name, String reading, List<String> also) { this(name, reading, also, "", ""); }
    }

    /**
     * One more name a person carried, as a read gives it: whose name ({@code person}, as the read writes that person), the name, its family
     * and given parts, its other written forms (kana, romaji), its kind ({@link FamilyNameHistory#KINDS}), the text's own words for how it
     * came ({@code said}), the date the text gives it ("event" when the text dates it by the event that caused it), and the quote.
     */
    public record NameRead(String person, String name, String family, String given, List<String> forms, String kind, String said, String date, String quote) {
        public NameRead {
            forms = forms == null ? List.of() : forms;
            family = family == null ? "" : family.strip();
            given = given == null ? "" : given.strip();
            kind = kind == null ? "" : kind.strip();
            said = said == null ? "" : said.strip();
            date = date == null ? "" : date.strip();
            quote = quote == null ? "" : quote;
        }
    }

    /** A family a read speaks of as a family: its name (森田), as the text writes it (森田家, the Morita family), its seat when the text names one, and the quote. */
    public record FamilyRead(String name, String written, String seat, String quote) {
        public FamilyRead {
            name = name == null ? "" : name.strip();
            written = written == null ? "" : written.strip();
            seat = seat == null ? "" : seat.strip();
            quote = quote == null ? "" : quote;
        }
    }

    /**
     * A fact as a read gives it. {@code detail}: the reading of the fact beyond its three words ({@link FamilyDetail}): how somebody entered a
     * family and when, an adoption's kind, whose family name a couple took. Kept with the claim as its detail note.
     */
    public record Fact(String subject, String relation, String object, String date, String quote, Map<String, String> detail) {
        public Fact { detail = detail == null || detail.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(detail)); }
        public Fact(String subject, String relation, String object, String date, String quote) { this(subject, relation, object, date, quote, Map.of()); }
    }

    /**
     * {@code twoWays}: a person the text reads two ways, as {name, one reading, the other}. {@code names}: the other names the people of the
     * read carried; {@code families}: the families it speaks of as families.
     */
    public record Read(List<Person> people, List<Fact> facts, List<String> dropped, List<String[]> twoWays, List<NameRead> names, List<FamilyRead> families) {
        public Read {
            twoWays = twoWays == null ? List.of() : twoWays;
            names = names == null ? List.of() : names;
            families = families == null ? List.of() : families;
        }
        public Read(List<Person> people, List<Fact> facts, List<String> dropped, List<String[]> twoWays) { this(people, facts, dropped, twoWays, List.of(), List.of()); }
        public Read(List<Person> people, List<Fact> facts, List<String> dropped) { this(people, facts, dropped, List.of()); }

        /** This read with other people, facts, dropped and two-way readings, and its names and families as they were. */
        public Read with(List<Person> people, List<Fact> facts, List<String> dropped, List<String[]> twoWays) { return new Read(people, facts, dropped, twoWays, names, families); }
    }

    /**
     * {@code sourcesAdded}: facts the library already had, which this text now backs as a further source. {@code twoWays}: a sentence for
     * each person the text reads two ways. {@code mayBeLiving}: how many of the read's people nothing in the library places in the past, once
     * the read is filed. {@code names}: the name claims the read wrote or backed; {@code families}: the families it found or made;
     * {@code mentions}: the people it wrote down as written only by a family name; {@code linked}: a sentence for each of those the library
     * then linked to a person, with the command that takes the link back; {@code questions}: the codes of the questions about names and
     * families the read raised ({@link FamilyNameQuestions}).
     */
    public record Outcome(int people, int claims, int alreadyHeld, int mayBeLiving, List<String> dropped, int sourcesAdded, List<String> twoWays,
                          int names, int families, int mentions, List<String> linked, List<String> questions) {
        public Outcome(int people, int claims, int alreadyHeld, int mayBeLiving, List<String> dropped, int sourcesAdded, List<String> twoWays) { this(people, claims, alreadyHeld, mayBeLiving, dropped, sourcesAdded, twoWays, 0, 0, 0, List.of(), List.of()); }
        public Outcome(int people, int claims, int alreadyHeld, int mayBeLiving, List<String> dropped) { this(people, claims, alreadyHeld, mayBeLiving, dropped, 0, List.of()); }
    }

    /**
     * One prose completion: the prompt in, the model's words out. A model that gives no answer at all throws {@link Unanswered}: that ends
     * the read of the text, and nothing of it is filed.
     */
    public interface Model { String answer(String prompt); }

    /**
     * The model gave no answer at all: it could not be reached (the connection was refused, no route, no such host), it took the question
     * and did not answer (in time, or the connection broke), or its server answered with an error in place of an answer ({@link
     * DriveClient.ErrorStatus}: still loading, a key it does not take, no such model, too many requests). Not a reply that was read and said
     * nothing. Nothing of the text is filed then, and the file is not written down as read, so the next read does the whole of it.
     */
    public static final class Unanswered extends RuntimeException {
        private final String address;
        private final boolean reached;
        /** The HTTP status the server answered with in place of an answer; 0 for none. */
        private final int status;
        private final String serverSaid;

        public Unanswered(String address, Throwable cause) {
            super("the model at " + address + how(reached(cause), status(cause)), cause);
            this.address = address;
            this.reached = reached(cause);
            this.status = status(cause);
            String m = "";
            for (Throwable c = cause; c != null; c = c.getCause()) if (c instanceof DriveClient.ErrorStatus e) { m = e.getMessage() == null ? "" : e.getMessage().strip(); break; }
            this.serverSaid = m;
        }

        /** Whether the model could not be reached at all: every other file would wait for it in vain. */
        public boolean unreachable() { return !reached; }

        /** What happened, for the person: that {@code what} ("the file", "a-notes.txt") was not read, and nothing of it filed. */
        public String said(String what) {
            return "The model at " + address + how(reached, status) + ", so the library read nothing from " + what + " and added nothing from it to your library.";
        }

        /** What to do about it, with the same command given again. */
        public String todo() {
            String again = ", then give the same command again.";
            if (status > 0 && Bedrock.is(address) && !serverSaid.isEmpty()) return serverSaid + (serverSaid.endsWith(".") ? "" : ".") + " Then give the same command again.";
            return switch (status) {
                case 503 -> "Its server said the model is still loading or busy. Wait a few minutes" + again;
                case 429 -> "Its server said it has too many requests right now. Wait a few minutes" + again;
                case 401, 403 -> "Its server did not accept the key. Check the key in the setting RESEARCHZOSHO_API_KEY" + again;
                case 404 -> "Its server has no model or page at that address. Check the address (the setting RESEARCHZOSHO_DRIVE) and the model's name (the setting RESEARCHZOSHO_MODEL)" + again;
                case 502, 504 -> "The server in front of the model got no answer from it. Check that the model is running" + again;
                default -> reached ? "Check that the model at " + address + " is running and not busy with other work" + again
                        : "Start the model, or check that " + address + " is the right address of the model (the setting RESEARCHZOSHO_DRIVE)" + again;
            };
        }

        private static String how(boolean reached, int status) {
            return status > 0 ? " answered with an error instead of an answer (HTTP " + status + ")" : reached ? " did not answer" : " could not be reached";
        }

        private static int status(Throwable cause) {
            for (Throwable c = cause; c != null; c = c.getCause()) if (c instanceof DriveClient.ErrorStatus e) return e.status;
            return 0;
        }

        private static boolean reached(Throwable cause) {
            for (Throwable c = cause; c != null; c = c.getCause())
                if (c instanceof ConnectException || c instanceof NoRouteToHostException || c instanceof UnknownHostException || c instanceof HttpConnectTimeoutException) return false;
            return true;
        }
    }

    /** A yes/no with a probability, when the drive gives token probabilities: the reader's confirm step takes it before the one-word answer. */
    public interface YesNo { double pYes(String question); }

    /** Read the text piece by piece. A fact stays only when the sentence given for it is in the text. */
    public static Read read(String text, String teller, List<Vocabulary.Term> relations, Model model) { return read(text, teller, relations, model, List.of(), null); }

    /**
     * {@code families}: family names to keep to (a whole book about a town names hundreds of people; the reader wants the 髙橋 and the
     * Takahashi in it). A fact stays when its subject or its object carries one of the names, in either character form; none given keeps
     * everything. {@code progress} hears "piece 3 of 85" as the reading goes, because a book takes hours on a small machine.
     */
    public static Read read(String text, String teller, List<Vocabulary.Term> relations, Model model, List<String> families, Consumer<String> progress) { return read(text, teller, relations, model, families, progress, null); }

    public static Read read(String text, String teller, List<Vocabulary.Term> relations, Model model, List<String> families, Consumer<String> progress, YesNo judge) { return read(text, teller, relations, model, families, progress, judge, null); }

    /**
     * Who wrote a text, and where it is: {@code writer} is a name the person gave ("Kimie Hale wrote community.pdf"), {@code where} the file's
     * name or the page's address, {@code book} whether it is a book or another long document. A book quotes other people in the first
     * person: an interview, a letter, a memoir. {@code translator}: who translated it, "" for nobody known; a translator's own remarks are not
     * the writer's words. {@code page}: a web page, which is nobody's "I": {@code where} is then the page's title and host, the source the
     * facts come from, and {@code writer} the stand-in for whoever wrote the page. {@code writtenAs}: the writer's name as the text's own
     * first pages write it, when that is another way of writing than the one the person gave ("Hisa Endo" for 遠藤久); "" otherwise.
     * {@code memoir}: the person said the text is the writer's own memoir, autobiography or diary, so its own text speaks as the writer.
     */
    public record Voice(String writer, String where, boolean book, String translator, boolean page, String writtenAs, boolean memoir) {
        public Voice(String writer, String where, boolean book) { this(writer, where, book, "", false, "", false); }

        /** This voice with the writer's name as the text writes it, and whether it is the writer's own memoir. */
        public Voice as(String written, boolean ownMemoir) {
            String w = written == null || written.isBlank() || flat(written).equals(flat(writer)) ? "" : written.strip();
            return new Voice(writer, where, book, translator, page, w, ownMemoir);
        }

        /** The voice of a text at a locator: a book by its file's kind, and a writer only when the person named one. */
        public static Voice of(String writer, String locator) { return of(writer, locator, ""); }

        /** The same, with the translator the person or the text named. */
        public static Voice of(String writer, String locator, String translator) {
            if (writer == null || writer.isBlank() || FamilyQuestions.placeholder(writer)) return null;
            String l = locator == null ? "" : locator.strip();
            String name = l.startsWith("file:") ? l.substring(l.lastIndexOf('/') + 1) : FamilyChecks.from(l);
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
            String t = translator == null || translator.isBlank() || FamilyQuestions.placeholder(translator) || isWriter(translator, writer) ? "" : translator.strip();
            return new Voice(writer.strip(), name, ext.equals("pdf") || ext.equals("epub"), t, false, "", false);
        }

        /** A web page, by its title and host ("森田健二 - Wikipedia (ja.wikipedia.org)"): the source of what it says, never a person in it. */
        public static Voice page(String titleAndHost) {
            String w = titleAndHost == null ? "" : titleAndHost.strip();
            return new Voice("the writer of the page " + w, w, false, "", true, "", false);
        }

        /** Whether a file at a locator is a book or another long document: a book's file, or a text this long. */
        public static boolean bookish(String locator, String text) {
            String l = locator == null ? "" : locator.strip().toLowerCase(Locale.ROOT);
            return l.endsWith(".pdf") || l.endsWith(".epub") || text != null && text.length() >= LONG_TEXT;
        }
    }

    /** A text this long is a book, whatever its file: first person in it may be somebody the writer quotes. */
    static final int LONG_TEXT = 40_000;

    /** Words that speak as I or we, in the languages the reader meets most; a relative named by the kin word alone (父は) speaks from inside a family too. */
    static final Pattern FIRST_PERSON = Pattern.compile("\\bI\\b|(?i:\\b(?:me|my|mine|we|us|our|ours)\\b)|私|僕|わたし|あたし|俺|我が|(?:^|[\\s、。「『])(?:父|母|祖父|祖母|兄|姉|弟|妹)は");

    public static Read read(String text, String teller, List<Vocabulary.Term> relations, Model model, List<String> families, Consumer<String> progress, YesNo judge, Voice voice) {
        return read(text, teller, relations, model, families, progress, judge, voice, List.of());
    }

    /**
     * The same, with the forms the library's families have in more than one script ({@link #familyForms}): words that write the 森田 family as
     * "the Morita family" speak of it as a family, as they do where this read itself ties the two.
     */
    public static Read read(String text, String teller, List<Vocabulary.Term> relations, Model model, List<String> families, Consumer<String> progress, YesNo judge, Voice voice, List<List<String>> heldFamilies) {
        Map<String, Person> people = new LinkedHashMap<>();
        // the forms of one family name in several scripts, as the library and this read tie them (森田 and Morita)
        List<List<String>> ties = new ArrayList<>(heldFamilies == null ? List.of() : heldFamilies);
        List<String[]> twoWays = new ArrayList<>();
        boolean longText = voice != null && !voice.page() && (voice.book() || (text != null && text.length() >= LONG_TEXT));
        // a web page is the source of what it says: the prompt names it as the source, and its "I" is whoever wrote it
        String source = voice != null && voice.page() ? voice.where() : "";
        List<Fact> facts = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        List<NameRead> names = new ArrayList<>();
        List<FamilyRead> familyReads = new ArrayList<>();
        Map<String, Set<String>> sexGiven = new LinkedHashMap<>();   // a person → the sex the model gave them
        Set<String> known = new LinkedHashSet<>();
        for (Vocabulary.Term t : relations) known.add(t.slug());
        // the family names the read's people carry, as the model gives them: beside such a name, "the Hales" or "the Hale line" is a family
        Set<String> personParts = new LinkedHashSet<>();
        String whole = flat(text);
        List<String> all = pieces(text);
        List<String> wanted = new ArrayList<>();
        for (String f : families) if (f != null && !f.isBlank()) wanted.add(flat(KanjiForms.modern(f)));
        int at = 0, skipped = 0;
        for (String piece : all) {
            at++;
            // a piece of a book that never names the family is not sent to the model at all
            if (!wanted.isEmpty()) { String p = flat(KanjiForms.modern(piece)); if (wanted.stream().noneMatch(p::contains)) { skipped++; continue; } }
            if (progress != null) progress.accept("reading part " + at + " of " + all.size() + (skipped > 0 ? " (" + skipped + " skipped: the family is not named in them)" : ""));
            List<JsonNode> answers = answers(piece, teller, source, relations, model, dropped, 2);
            for (JsonNode v : answers) {
            List<Person> piecePeople = new ArrayList<>();
            List<String> pieceNames = new ArrayList<>();
            for (String key : List.of("people", "names")) for (JsonNode x : v.path(key)) { String fam = x.path("family").asText("").strip(); if (!fam.isEmpty()) personParts.add(fam); }
            // and the families the text names in words that name a family by themselves (the Takahashi family, 髙橋家): "the Takahashi line" is that family
            for (JsonNode x : v.path("families")) for (String w : List.of(x.path("written").asText("").strip(), x.path("name").asText("").strip()))
                if (FamilyMentions.namesAFamily(w, Set.of()) && said(whole, x.path("quote").asText(""))) { personParts.add(FamilyHouses.familyName(w.replaceAll("\\s*[(（][^)）]*[)）]\\s*$", ""))); if (!x.path("name").asText("").isBlank()) personParts.add(x.path("name").asText("").strip()); }
            for (JsonNode p : v.path("people")) {
                String name = p.path("name").asText("").strip();
                if (name.isEmpty()) continue;
                List<String> also = new ArrayList<>();
                for (JsonNode x : p.path("also")) if (!x.asText("").isBlank()) also.add(x.asText().strip());
                // a name read one way here and another way there: the second reading is kept too, and the person is told
                String reading = p.path("reading").asText("").strip();
                Person before = people.get(name);
                if (before != null && !before.reading().isEmpty() && !reading.isEmpty() && !sameReading(before.reading(), reading)) {
                    also.add(reading);
                    if (twoWays.stream().noneMatch(t -> t[0].equals(name) && sameReading(t[2], reading))) twoWays.add(new String[]{name, before.reading(), reading});
                }
                Person read = new Person(name, reading, also, p.path("family").asText(""), p.path("given").asText(""));
                String sex = FamilyKin.sexWord(p.path("sex").asText(""));
                if (!sex.isEmpty()) sexGiven.computeIfAbsent(name, k -> new LinkedHashSet<>()).add(sex);
                piecePeople.add(read);
                pieceNames.add(name);
                people.merge(name, read, (old, now) -> new Person(name, old.reading().isEmpty() ? now.reading() : old.reading(), union(old.also(), now.also()),
                        old.family().isEmpty() ? now.family() : old.family(), old.given().isEmpty() ? now.given() : old.given()));
            }
            // a family the read speaks of in two scripts (森田, "the Morita family"), and a person whose own forms read the family part (森田健二,
            // もりた けんじ; 森田勇, Morita Isamu), tie the family name's forms
            for (JsonNode x : v.path("families")) if (said(whole, x.path("quote").asText(""))) ties.add(List.of(x.path("name").asText("").strip(), FamilyHouses.familyName(x.path("written").asText(""))));
            for (Person p : piecePeople) ties.addAll(ties(p));
            // a spelling in Latin letters the text never writes as the person's: taken off before the piece's facts are read with it
            alsoNotWrittenAsOne(people, text, dropped);
            for (JsonNode f : v.path("facts")) { pieceNames.add(f.path("subject").asText("").strip()); pieceNames.add(f.path("object").asText("").strip()); }
            // every way this piece writes its people: a part of one person's name found inside another's is that other person's
            List<String> pieceAll = new ArrayList<>(pieceNames);
            for (Person p : piecePeople) { pieceAll.addAll(p.also()); if (!p.reading().isBlank()) pieceAll.add(p.reading()); }
            for (JsonNode n : v.path("names")) pieceAll.add(n.path("name").asText("").strip());
            int namesBefore = names.size();
            for (JsonNode f : v.path("facts")) {
                String relation = relationOf(f.path("relation").asText("").strip(), relations);
                Map<String, String> detail = factDetail(f);
                if (!relation.equals(FamilyNameHistory.PREDICATE)) { detail.remove("family"); detail.remove("given"); detail.remove("said"); }
                Fact fact = new Fact(f.path("subject").asText("").strip(), relation, f.path("object").asText("").strip(), f.path("date").asText("").strip(), f.path("quote").asText("").strip(), detail);
                String say = sentence(fact.subject(), fact.relation(), fact.object());
                if (fact.subject().isEmpty() || fact.object().isEmpty()) continue;
                // a name given among the facts is one of the person's names, and is checked as a name is
                if (fact.relation().equals(FamilyNameHistory.PREDICATE)) {
                    if (!wanted.isEmpty()) { String so = flat(KanjiForms.modern(fact.subject() + " " + fact.object())); if (wanted.stream().noneMatch(so::contains)) continue; }
                    Map<String, String> d = fact.detail();
                    NameRead read = checkName(new NameRead(fact.subject(), FamilyNameHistory.bare(fact.object()), d.getOrDefault("family", ""), d.getOrDefault("given", ""), List.of(),
                            d.getOrDefault("kind", ""), d.getOrDefault("said", ""), fact.date(), fact.quote()), text, whole, model, judge, dropped, othersThan(new NameRead(fact.subject(), FamilyNameHistory.bare(fact.object()), "", "", List.of(), "", "", "", ""), pieceAll, people), people, teller);
                    NameRead n = read != null && nameAlone(read, people.get(read.person()), writtenAs(read.person(), teller, voice), dropped) ? null : read;
                    if (n != null && names.stream().noneMatch(o -> o.person().equals(n.person()) && o.name().equals(n.name()))) names.add(n);
                    continue;
                }
                // a record that says a parent or a spouse is unknown, or leaves the column empty, is evidence too, kept only on its own words
                String absent = absence(fact.object());
                if (absent != null) {
                    Fact marked = absent(fact, absent, text, whole, dropped);
                    if (marked == null || !known.contains(fact.relation())) continue;
                    if (!wanted.isEmpty()) { String so = flat(KanjiForms.modern(fact.subject())); if (wanted.stream().noneMatch(so::contains)) continue; }
                    if (facts.stream().noneMatch(o -> o.subject().equals(marked.subject()) && o.relation().equals(marked.relation()) && o.object().equals(marked.object()))) facts.add(marked);
                    continue;
                }
                if (!known.contains(fact.relation())) { if (fact.relation().contains("-")) dropped.add("\"" + say + "\" was left out, because the library does not have that kind of family relation."); continue; }
                if (!wanted.isEmpty()) { String so = flat(KanjiForms.modern(fact.subject() + " " + fact.object())); if (wanted.stream().noneMatch(so::contains)) continue; }
                if (said(whole, fact.quote())) {
                    // words that stand on one line of the text are a sentence. Words gathered across lines or table cells prove less, so for a
                    // family relation the model is asked once whether they say it: a table's "head of state" cell sits next to a name too
                    if (personToPerson(fact.relation()) && !onOneLine(text, fact.quote()) && !confirms(model, judge, fact, relations, "")) { dropped.add("\"" + say + "\" was left out. It was taken from a table (\"" + Acquisitions.compress(fact.quote(), 60) + "\"), and on a second look those words do not say it."); continue; }
                } else {
                    // a table says a thing across its cells, and the model joins them into one line: the pieces must stand close together
                    // in the text, and because joined words prove less than a sentence, the model is asked once whether they say it
                    String near = stitched(whole, fact.quote());
                    boolean holds = near != null && (!personToPerson(fact.relation()) || confirms(model, judge, fact, relations, near));
                    if (!holds) { dropped.add("\"" + say + "\" was left out" + (near == null ? ", because the library could not find the words in the text that say it. The words it was given were: \"" : ". The words are in the text (\"") + Acquisitions.compress(fact.quote(), 60) + "\"" + (near == null ? "" : "), and on a second look they do not say it.")); continue; }
                }
                // a family is a family when the words name it as one: 森田家, the Morita family, the house of Hale. Words whose family word stands
                // beside no name ("entered the family") name no family, and there is nothing to ask about
                if (fact.relation().equals(FamilyHouses.MEMBER) && !FamilyMentions.properName(FamilyHouses.familyName(fact.object()))) {
                    dropped.add("\"" + say + "\" was left out, because the words given for it (\"" + Acquisitions.compress(fact.quote(), 60) + "\") do not name the family.");
                    continue;
                }
                if (fact.relation().equals(FamilyHouses.MEMBER) && !namedAsFamily(fact.object(), fact.quote(), personParts, ties)
                        && !sure(model, judge, checkQuestion(fact.quote(), fact.subject() + " was a member of the " + FamilyHouses.familyName(fact.object()) + " family, a family as a whole (a house, a line or a 家)"))) {
                    dropped.add("\"" + say + "\" was left out, because the words given for it (\"" + Acquisitions.compress(fact.quote(), 60) + "\") do not speak of " + fact.object() + " as a family.");
                    continue;
                }
                Fact worded = relationByWords(anchoredByWords(directedByWords(heirAsked(kindsInWords(fact, model, judge), model, judge), teller, people, dropped), teller, people, dropped), teller, people, personParts, known, dropped);
                if (worded == null) continue;
                Fact kept = longText ? voiced(worded, voice, piece, text, at, judge, dropped) : worded;
                if (facts.stream().noneMatch(o -> o.subject().equals(kept.subject()) && o.relation().equals(kept.relation()) && o.object().equals(kept.object()))) facts.add(nearest(kept, piece, piecePeople, pieceNames));
            }
            for (JsonNode n : v.path("names")) {
                String person = n.path("person").asText("").strip(), name = n.path("name").asText("").strip();
                if (person.isEmpty() || name.isEmpty()) continue;
                if (!wanted.isEmpty()) { String so = flat(KanjiForms.modern(person + " " + name)); if (wanted.stream().noneMatch(so::contains)) continue; }
                List<String> forms = new ArrayList<>();
                for (String key : List.of("also", "forms")) for (JsonNode x : n.path(key)) if (!x.asText("").isBlank()) forms.add(x.asText().strip());
                NameRead read = checkName(new NameRead(person, name, n.path("family").asText(""), n.path("given").asText(""), forms, n.path("kind").asText(""), n.path("said").asText(""),
                        n.path("date").asText(""), n.path("quote").asText("").strip()), text, whole, model, judge, dropped, othersThan(new NameRead(person, name, "", "", forms, "", "", "", ""), pieceAll, people), people, teller);
                NameRead checked = read != null && nameAlone(read, people.get(read.person()), writtenAs(read.person(), teller, voice), dropped) ? null : read;
                if (checked != null && names.stream().noneMatch(o -> o.person().equals(checked.person()) && o.name().equals(checked.name()))) names.add(checked);
            }
            // a name whose words give it to a relative ("her husband Tom Hart", 妻ハル) is the relative's, not the person's
            ofRelatives(names, namesBefore, people, facts, sexGiven, personParts, known, teller, longText ? voice : null, piece, text, at, judge, dropped);
            for (JsonNode fr : v.path("families")) {
                String name = fr.path("name").asText("").strip(), written = fr.path("written").asText("").strip();
                if (name.isEmpty() && written.isEmpty()) continue;
                if (!wanted.isEmpty()) { String so = flat(KanjiForms.modern(name + " " + written)); if (wanted.stream().noneMatch(so::contains)) continue; }
                FamilyRead checked = checkFamily(new FamilyRead(name, written, fr.path("seat").asText(""), fr.path("quote").asText("").strip()), text, whole, model, judge, dropped, personParts);
                if (checked != null && familyReads.stream().noneMatch(o -> o.name().equals(checked.name()) && o.seat().equals(checked.seat()))) familyReads.add(checked);
            }
            }
        }
        // a book's index writes a relation word beside a name: a relative of the book's writer, whom the notes name
        if (voice != null && !voice.page()) indexRelatives(text, voice.writer(), people, facts, known, wanted, dropped);
        // another spelling the model gave a person that is somebody else of the read, seen over the whole read
        alsoOfOthers(people, facts, text, dropped);
        // a name that is somebody else of this read, seen over the whole read: the other person's facts may come pieces after the name
        names = ofOthers(names, new ArrayList<>(people.values()), facts, teller, text, dropped);
        // a relative the account does not name is written with the account's own words for them: words no fact about them carries were made up
        List<Fact> said = relativesSaid(facts, whole, model, judge, dropped);
        facts.retainAll(said);
        facts.addAll(sexesInWords(new ArrayList<>(people.values()), facts, names, sexGiven));
        Read done = new Read(new ArrayList<>(people.values()), principal(mukoyoshiHalves(facts, names, familyReads, new ArrayList<>(people.values())), dropped), dropped, twoWays, names, familyReads);
        if (voice != null && voice.page()) done = pageNoParty(done, voice.where(), text);
        return charactersFirst(writtenAsInText(done, text, teller), text, teller);
    }

    /**
     * Each person the text writes both in characters and in Latin letters or kana, under the name in characters: the characters tell who a
     * person is, and they are what the family reads. The characters count when the model gives them among the person's other spellings and the
     * text writes them with the name, one in the brackets right after the other ("Morita Kenji (森田健二, もりた けんじ)"), or with a reading in their
     * brackets that spells the name (森田健二（もりた けんじ）beside Morita Kenji). The Latin letters and the kana stay as other spellings. A family
     * the text writes in characters (森田家), in the brackets after its name in Latin letters ("the Takahashi family (髙橋家)") or as the words the
     * model gives for it, takes its name in characters, and the Latin words stay the way the text wrote it.
     */
    static Read charactersFirst(Read read, String text, String teller) {
        Map<String, String> renamed = new LinkedHashMap<>();
        Set<String> taken = new HashSet<>(), words = nameWords(read);
        for (Person p : read.people()) taken.add(p.name());
        for (Person p : read.people()) {
            String sc = FamilyForms.script(p.name());
            if (!(sc.equals("latin") || sc.equals("kana")) || p.name().equals(teller) || FamilyQuestions.placeholder(p.name()) || FamilyMentions.isMention(p.name())) continue;
            for (String a : p.also()) {
                if (!FamilyForms.script(a).equals("han") || taken.contains(a) || !charactersOf(text, p.name(), a, words)) continue;
                renamed.put(p.name(), a);
                taken.add(a);
                break;
            }
        }
        List<FamilyRead> families = new ArrayList<>();
        boolean familyRenamed = false;
        String t = inTextKey(text);
        for (FamilyRead f : read.families()) {
            String name = f.name().isBlank() ? FamilyHouses.familyName(f.written()) : f.name();
            String han = null;
            if (FamilyForms.script(name).equals("latin")) {
                String bare = f.written().replaceAll("\\s*[(（][^)）]*[)）]\\s*$", "").strip();
                if (FamilyForms.script(bare).equals("han") && standsWhole(bare, t)) han = FamilyHouses.familyName(bare);
                for (String way : List.of(f.written(), "the " + name + " family", name + " family")) {
                    if (han != null || way.isBlank()) continue;
                    for (String item : bracketed(text, way, "", Set.of())) if (FamilyForms.script(item).equals("han") && FamilyHouses.familyWord(item)) { han = FamilyHouses.familyName(item); break; }
                }
            }
            if (han == null || !FamilyForms.script(han).equals("han")) { families.add(f); continue; }
            families.add(new FamilyRead(han, f.written().isBlank() ? name : f.written(), f.seat(), f.quote()));
            familyRenamed = true;
        }
        if (renamed.isEmpty() && !familyRenamed) return read;
        List<Person> people = new ArrayList<>();
        for (Person p : read.people()) {
            String to = renamed.get(p.name());
            if (to == null) { people.add(p); continue; }
            List<String> also = new ArrayList<>(List.of(p.name()));
            for (String a : p.also()) if (!a.equals(to) && !also.contains(a)) also.add(a);
            people.add(new Person(to, p.reading(), also, p.family(), p.given()));
        }
        Function<String, String> as = n -> renamed.getOrDefault(n, n);
        List<Fact> facts = new ArrayList<>();
        for (Fact f : read.facts()) facts.add(new Fact(as.apply(f.subject()), f.relation(), personToPerson(f.relation()) || associate(f.relation()) ? as.apply(f.object()) : f.object(), f.date(), f.quote(), f.detail()));
        List<NameRead> names = new ArrayList<>();
        for (NameRead n : read.names()) names.add(new NameRead(as.apply(n.person()), n.name(), n.family(), n.given(), n.forms(), n.kind(), n.said(), n.date(), n.quote()));
        List<String[]> twoWays = new ArrayList<>();
        for (String[] w : read.twoWays()) twoWays.add(new String[]{as.apply(w[0]), w[1], w[2]});
        return new Read(people, facts, read.dropped(), twoWays, names, families);
    }

    /** Whether the text writes these characters as this name: one in the brackets right after the other, or the characters with a reading in their brackets that spells the name. */
    private static boolean charactersOf(String text, String name, String han, Set<String> words) {
        if (!bracketed(text, name, han, words).isEmpty() || !bracketed(text, han, name, words).isEmpty()) return true;
        for (String r : bracketed(text, han, "", words)) if (FamilyForms.script(r).equals("kana") && FamilyForms.sameForm(r, name, true)) return true;
        return false;
    }

    // a name in Latin letters: two words, each beginning with a capital ("Endō Toku")
    private static final Pattern LATIN_NAME = Pattern.compile("(?<![\\p{L}\\p{M}])\\p{Lu}[\\p{L}\\p{M}]+\\s+\\p{Lu}[\\p{L}\\p{M}]+(?![\\p{L}\\p{M}])");

    /**
     * For a person the model gave only in a form the text never writes, with no other spelling: the name in Latin letters that the quotes of the
     * person's own facts write, when one of its words is the family part the read's other people give that family in Latin letters (遠藤正一 and
     * Endō Shōichi: 遠藤 is Endō) and it is nobody else's name. Null unless there is exactly one.
     */
    private static String quotedName(Read read, Person p, String textKey) {
        if (p.family().isBlank() || !p.also().isEmpty() || !p.reading().isBlank()) return null;
        Set<String> tied = new HashSet<>(), others = new HashSet<>();
        for (Person o : read.people()) {
            if (o == p) continue;
            others.add(flat(o.name()));
            for (String a : o.also()) others.add(flat(a));
            for (List<String> tie : ties(o)) if (FamilyForms.sameForm(tie.get(0), p.family(), true)) for (String w : tie.subList(1, tie.size())) tied.add(flat(w));
        }
        Set<String> found = new LinkedHashSet<>();
        for (Fact f : read.facts()) {
            if (!f.subject().equals(p.name()) && !f.object().equals(p.name())) continue;
            for (Matcher m = LATIN_NAME.matcher(f.quote()); m.find(); ) {
                String n = m.group();
                if (!others.contains(flat(n)) && standsWhole(n, textKey) && Arrays.stream(n.split("\\s+")).anyMatch(w -> tied.contains(flat(w)))) found.add(n);
            }
        }
        return found.size() == 1 ? found.iterator().next() : null;
    }

    /**
     * Each person under a name the text writes: a name the model wrote in a form the text never uses (katakana for a name the text gives only in
     * Latin letters) becomes the form the text writes, from the other spellings the model gave. A name built from parts the text writes (a
     * register's household family name and the given name on the member's line) is the text's own. The form nobody wrote is kept nowhere.
     */
    static Read writtenAsInText(Read read, String text, String teller) {
        String t = inTextKey(text);
        Map<String, String> renamed = new LinkedHashMap<>();
        for (Person p : read.people()) {
            if (p.name().equals(teller) || FamilyQuestions.placeholder(p.name()) || FamilyMentions.isMention(p.name()) || inText(p.name(), p, t)) continue;
            List<String> ways = new ArrayList<>(p.also());
            if (!p.reading().isBlank()) ways.add(p.reading());
            // a whole name before a given name alone: "Morita Isamu" and "Isamu" both stand in the text, and the person is Morita Isamu
            List<String> standing = new ArrayList<>();
            for (String a : ways) if (!a.isBlank() && !a.equals(p.name()) && standsWhole(a, t) && read.people().stream().noneMatch(o -> o != p && o.name().equals(a))) standing.add(a);
            standing.stream().filter(FamilyMentions::full).findFirst().or(() -> standing.stream().findFirst()).ifPresent(a -> renamed.put(p.name(), a));
            // no other spelling given: the name in Latin letters the person's own sentences write for that family
            String quoted = standing.isEmpty() ? quotedName(read, p, t) : null;
            if (quoted != null && read.people().stream().noneMatch(o -> o.name().equals(quoted))) renamed.put(p.name(), quoted);
        }
        if (renamed.isEmpty()) return read;
        List<String> dropped = new ArrayList<>(read.dropped());
        List<Person> people = new ArrayList<>();
        for (Person p : read.people()) {
            String to = renamed.get(p.name());
            if (to == null) { people.add(p); continue; }
            List<String> also = new ArrayList<>(p.also());
            also.remove(to);
            dropped.add(p.name() + " is not written in the text, so the library writes this person as the text does: " + to + ".");
            boolean sameScript = FamilyForms.script(to).equals(FamilyForms.script(p.name()));
            people.add(new Person(to, p.reading().equals(to) ? "" : p.reading(), also, sameScript ? p.family() : "", sameScript ? p.given() : ""));
        }
        Function<String, String> as = n -> renamed.getOrDefault(n, n);
        List<Fact> facts = new ArrayList<>();
        for (Fact f : read.facts()) facts.add(new Fact(as.apply(f.subject()), f.relation(), personToPerson(f.relation()) || associate(f.relation()) ? as.apply(f.object()) : f.object(), f.date(), f.quote(), f.detail()));
        List<NameRead> names = new ArrayList<>();
        for (NameRead n : read.names()) names.add(new NameRead(as.apply(n.person()), n.name(), n.family(), n.given(), n.forms(), n.kind(), n.said(), n.date(), n.quote()));
        List<String[]> twoWays = new ArrayList<>();
        for (String[] w : read.twoWays()) twoWays.add(new String[]{as.apply(w[0]), w[1], w[2]});
        return new Read(people, facts, dropped, twoWays, names, read.families());
    }

    /** Text in one form for looking a name up in it: modern characters, katakana as hiragana, Latin letters without their accents, no spaces or punctuation. */
    private static String inTextKey(String s) {
        String n = flat(KanjiForms.modern(s == null ? "" : s));
        StringBuilder b = new StringBuilder();
        n.codePoints().forEach(c -> b.appendCodePoint(c >= 0x30a1 && c <= 0x30f6 ? c - 0x60 : c));
        return b.toString();
    }

    /** Whether a text writes a name, in any of its forms ({@link #standsWhole}). */
    public static boolean writes(String text, String name) { return standsWhole(name, inTextKey(text)); }

    /** Whether a name stands whole in the text (in modern or old characters, in either kana, with or without accents, in either order). */
    private static boolean standsWhole(String name, String textKey) {
        String n = name == null ? "" : name.replaceAll("\\s*[(（][^)）]*[)）]\\s*$", "").strip();
        if (n.isEmpty()) return false;
        if (textKey.contains(inTextKey(n))) return true;
        if (FamilyForms.script(n).equals("latin")) for (String f : FamilyForms.forms(n, List.of())) if (FamilyForms.script(f).equals("latin") && textKey.contains(inTextKey(f))) return true;
        return false;
    }

    /**
     * Whether a person's name is one the text writes: whole, or built of parts the text writes in the name's own script: the family and given
     * parts the read gives, or in characters a beginning and a rest that each stand in the text (a rest in kana of two or more), or in Latin
     * letters every word.
     */
    private static boolean inText(String name, Person p, String textKey) {
        if (standsWhole(name, textKey)) return true;
        String n = name.replaceAll("\\s*[(（][^)）]*[)）]\\s*$", "").strip();
        String sc = FamilyForms.script(n);
        if (!p.family().isBlank() && !p.given().isBlank()) {
            String k = inTextKey(n);
            if (inTextKey(p.family() + p.given()).equals(k) || inTextKey(p.given() + p.family()).equals(k)) return standsWhole(p.family(), textKey) && standsWhole(p.given(), textKey);
        }
        if (sc.equals("latin")) {
            for (String w : n.split("[\\s,]+")) if (!w.isBlank() && !standsWhole(w, textKey)) return false;
            return true;
        }
        String joined = n.replaceAll("[\\s　・]+", "");
        for (int i = 1; i < joined.length(); i++) {
            String head = joined.substring(0, i), rest = joined.substring(i);
            boolean kanaRest = FamilyForms.script(rest).equals("kana");
            if ((!kanaRest || rest.length() >= 2) && textKey.contains(inTextKey(head)) && textKey.contains(inTextKey(rest))) return true;
        }
        return false;
    }

    // ── names, families and the words that say them ──────────────────────────────────────────────────────────────────

    /** The reading of a fact beyond its three words, as the model gives it: how somebody entered or left a family, a role, an adoption's kind. */
    private static final List<String> DETAIL_KEYS = List.of("how", "left", "role", "kind", "took", "ended", "to", "family", "given", "said");

    /** A fact's reading from the model's answer, the values as words the library keeps ({@link FamilyDetail}), and whether the model says a party is written only by a family name. */
    static Map<String, String> factDetail(JsonNode f) {
        Map<String, String> d = new LinkedHashMap<>();
        JsonNode nested = f.path("detail");
        for (String k : DETAIL_KEYS) {
            String v = f.path(k).isValueNode() ? f.path(k).asText("").strip() : "";
            if (v.isEmpty() && nested.isObject()) v = nested.path(k).asText("").strip();
            if (v.isEmpty()) continue;
            d.put(k, Set.of("family", "given", "said").contains(k) ? v : v.toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-'));
        }
        JsonNode only = f.path("only_family_name");
        if (only.asBoolean(false) || only.asText("").equalsIgnoreCase("true")) d.put(FamilyMentions.MARKED, "true");
        return d;
    }

    /** Whether the one-word yes of the model, or the judge's sure yes, says the words say it. */
    private static boolean sure(Model model, YesNo judge, String question) {
        if (judge != null) return judge.pYes(question) >= 0.5 + Judge.SURE / 2;
        String raw = model.answer(question + "\nAnswer with one word: yes or no.");
        return raw != null && raw.strip().toLowerCase(Locale.ROOT).replaceAll("(?s).*</think>", "").strip().startsWith("yes");
    }

    /** The question whether a quote says a thing the reader concluded from it. */
    static String checkQuestion(String quote, String concluded) {
        return "A reader took these words from a family's account: \"" + quote + "\".\nThe reader concluded: " + concluded + ".\n"
                + "Do the quoted words say that? They say it when they state it in words, in any language: how the name came, how the person came into the family, or the family as a whole.";
    }

    /** Words that speak of an adoption as for an heir. Defaults behind the rule that a kind is kept only when the words say it. */
    static final Pattern HEIR_WORDS = Pattern.compile("(?i)家督|跡を継|跡継|跡取|後継|継嗣|出嗣|入嗣|家を継|\\bheir\\b|\\bsucceed|\\b(?:carr(?:y|ies|ied|ying)|t(?:ake|akes|ook|aking)) (?:on|over) (?:the |his |her |their )?\\S+ (?:line|family|house|name)\\b");
    /** Words that say somebody was the head of a family. */
    private static final Pattern HEAD_WORDS = Pattern.compile("(?i)\\bhead\\b|戸主|当主|家長|筆頭|家督");

    /**
     * A fact's kind words kept only where the quote says them: an adoption's kind (婿養子, as heir), how somebody entered a family, and a role
     * (head, heir). The default words are looked for first; for any other wording the judge is asked once. What the words do not say
     * becomes "unstated", and a role is left out: the family is asked.
     */
    static Fact kindsInWords(Fact f, Model model, YesNo judge) {
        if (f.detail().isEmpty()) return f;
        Map<String, String> d = new LinkedHashMap<>(f.detail());
        String q = f.quote();
        if (f.relation().equals("adopted-by") && d.containsKey("kind")) {
            String k = d.get("kind");
            k = k.equals("婿養子") ? "mukoyoshi" : k.equals("adoptive") || k.equals("adoption") ? "ordinary" : k;
            boolean says = switch (k) {
                case "mukoyoshi" -> FamilyNameHistory.saysKind(q, "mukoyoshi");
                case "heir" -> HEIR_WORDS.matcher(q).find();
                case "ordinary" -> FamilyNameHistory.saysKind(q, "adoptive");
                default -> false;
            };
            if (!says && !k.equals("unstated") && !sure(model, judge, checkQuestion(q, sentence(f.subject(), f.relation(), f.object(), Map.of("kind", k))))) k = "unstated";
            d.put("kind", k);
        }
        if (f.relation().equals(FamilyHouses.MEMBER) && d.containsKey("how")) {
            String how = d.get("how");
            how = how.equals("婿養子") ? "mukoyoshi" : how.equals("入夫") ? "nyufu" : how.equals("adopted") || how.equals("adoptive") ? "adoption" : how.equals("married") ? "marriage" : how;
            String kind = switch (how) { case "adoption" -> "adoptive"; case "mukoyoshi", "nyufu", "marriage", "succession", "birth" -> how; default -> ""; };
            if (!kind.isEmpty() && !FamilyNameHistory.saysKind(q, kind) && !sure(model, judge, checkQuestion(q, FamilyHouses.sentence(f.subject(), f.object(), Map.of("how", how))))) how = "unstated";
            d.put("how", how);
        }
        if (f.relation().equals(FamilyHouses.MEMBER) && d.containsKey("role")) {
            String role = d.get("role");
            boolean says = role.equals("head") ? HEAD_WORDS.matcher(q).find() : role.equals("heir") ? HEIR_WORDS.matcher(q).find() : true;
            if (!says && !sure(model, judge, checkQuestion(q, FamilyHouses.sentence(f.subject(), f.object(), Map.of("role", role))))) d.remove("role");
        }
        return d.equals(f.detail()) ? f : new Fact(f.subject(), f.relation(), f.object(), f.date(), f.quote(), d);
    }

    /**
     * An adoption the model gives without its kind, in words that say heir away from the family's own words ("…was adopted into the Takahashi
     * family, and became its heir", 髙橋家の養子となり、家督を相続した): the judge is asked once whether the words make it an adoption as heir.
     * An heir word beside the family's own words needs no question ({@link FamilyMentions#heirBeside}), and words that say 婿養子 are read as
     * 婿養子 ({@link #mukoyoshiHalves}).
     */
    static Fact heirAsked(Fact f, Model model, YesNo judge) {
        if (!f.relation().equals("adopted-by") || !f.detail().getOrDefault("kind", "").isEmpty() || !HEIR_WORDS.matcher(f.quote()).find() || FamilyMentions.heirBeside(f.quote(), f.object())
                || FamilyNameHistory.saysKind(f.quote(), "mukoyoshi")) return f;
        return sure(model, judge, checkQuestion(f.quote(), sentence(f.subject(), f.relation(), f.object(), Map.of("kind", "heir")))) ? withDetail(f, "kind", "heir") : f;
    }

    /**
     * Whether the words name a family as a family: the object carries a family word beside a name (森田家, the Morita family), or the quote puts
     * one beside the family's name; words that say only "house", "line", "clan" or "the Xs" name a family when X is a family name a person of
     * the read carries ({@link FamilyMentions#namesAFamily}), because a parliament, a religious society or a band is written the same way.
     */
    static boolean namedAsFamily(String object, String quote) { return namedAsFamily(object, quote, Set.of()); }

    static boolean namedAsFamily(String object, String quote, Set<String> personParts) {
        String name = FamilyHouses.familyName(object);
        if (!FamilyMentions.properName(name) || !flat(quote).contains(flat(name))) return false;
        if (FamilyMentions.namesAFamily(object, personParts)) return true;
        String phrase = FamilyMentions.familyPhrase(name, quote);
        return phrase != null && FamilyMentions.namesAFamily(phrase, personParts);
    }

    /**
     * The same, where the words may write the family in another script than the fact: "the Morita family" names 森田 as a family when a reading
     * the read or the library gives ties the two ({@code ties}, {@link #otherScripts}).
     */
    static boolean namedAsFamily(String object, String quote, Set<String> personParts, Collection<List<String>> ties) {
        if (namedAsFamily(object, quote, personParts)) return true;
        String name = FamilyHouses.familyName(object);
        if (!FamilyMentions.properName(name)) return false;
        String q = unaccented(quote);
        for (String other : otherScripts(name, ties))
            for (String w : FamilyForms.script(other).equals("kana") ? FamilyForms.romaji(other) : List.of(other)) {
                String phrase = FamilyMentions.familyPhrase(unaccented(w), q);
                if (phrase != null && FamilyMentions.namesAFamily(phrase, personParts)) return true;
            }
        return false;
    }

    /** Latin letters without their accents, everything else as it is: "the Endō family" is "the Endo family". */
    private static String unaccented(String s) {
        return Normalizer.normalize(Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKD).replaceAll("(?<=\\p{IsLatin})\\p{M}+", ""), Normalizer.Form.NFKC);
    }

    /**
     * The forms of a family name in the other scripts that {@code ties} give it: each tie is the forms of one family name, as the read or the
     * library pairs them (森田 and Morita), and a form of the name in one of them brings the others of another script.
     */
    static List<String> otherScripts(String name, Collection<List<String>> ties) {
        String sc = FamilyForms.script(name);
        Set<String> out = new LinkedHashSet<>();
        if (ties == null || name == null || name.isBlank()) return List.of();
        for (List<String> tie : ties) {
            boolean jp = tie.stream().anyMatch(FamilyForms::japanese);
            if (tie.stream().noneMatch(t -> t != null && FamilyForms.sameForm(t, name, jp))) continue;
            for (String t : tie) if (t != null && !t.isBlank() && !FamilyForms.script(t).equals(sc)) out.add(t.strip());
        }
        return new ArrayList<>(out);
    }

    /**
     * The forms of a person's family part in another script that the person's own forms give: the first word of the reading of the name, when
     * the name begins with the family part (森田健二, もりた けんじ: 森田 is もりた), and the words of a form in Latin letters beside the one form in
     * characters the person has (森田勇, Morita Isamu: 森田 is Morita or Isamu, and the words that name a family say which).
     */
    static List<List<String>> ties(Person p) {
        List<List<String>> out = new ArrayList<>();
        String fam = p.family();
        if (!FamilyForms.script(fam).equals("han") || !FamilyForms.hanKey(p.name()).startsWith(FamilyForms.hanKey(fam))) return out;
        if (FamilyForms.script(p.reading()).equals("kana")) {
            String[] w = p.reading().strip().split("[\\s　・]+");
            if (w.length >= 2) out.add(List.of(fam, w[0]));
        }
        long inCharacters = union(List.of(p.name()), p.also()).stream().filter(x -> FamilyForms.script(x).equals("han")).count();
        if (inCharacters == 1) for (String x : p.also()) {
            String[] w = x.strip().split("[\\s,]+");
            if (FamilyForms.script(x).equals("latin") && w.length == 2) out.add(List.of(fam, w[0], w[1]));
        }
        return out;
    }

    /**
     * The forms each family of the library has in more than one script: the name in its label and the names in its other names (the 森田 family,
     * also written the Morita family: 森田 and Morita). The reader takes them as ties ({@link #read(String, String, List, Model, List, Consumer, YesNo, Voice, List)}).
     */
    public static List<List<String>> familyForms(Graph g) {
        List<List<String>> out = new ArrayList<>();
        if (g == null) return out;
        for (String id : FamilyHouses.all(g)) {
            List<String> forms = new ArrayList<>();
            for (String w : union(List.of(FamilyHouses.labelOf(g, id)), FamilyHouses.aliasesOf(g, id))) {
                String n = FamilyHouses.nameOf(w);
                if (FamilyMentions.properName(n) && forms.stream().noneMatch(f -> f.equals(n))) forms.add(n);
            }
            if (forms.stream().map(FamilyForms::script).distinct().count() >= 2) out.add(forms);
        }
        return out;
    }

    /** The ways a name stands in words: the name and the forms the read gives it, in modern and old characters, the romaji of its kana, both orders. */
    static List<String> writtenForms(String name, List<String> forms) {
        List<String> out = new ArrayList<>();
        for (String f : FamilyForms.forms(name, forms)) if (flat(f).length() >= 2 && !out.contains(f)) out.add(f);
        return out;
    }

    /** Whether a name stands in its quote: one of its written forms whole, or its family part and its given part each in one of their forms. */
    static boolean nameInQuote(NameRead n) { return nameInQuote(n, List.of()); }

    /**
     * The same, where the parts on their own count only outside the names of the read's other people ({@code others}): a family name that
     * stands only in his wife's name ("married Morita Haru") is hers, and says nothing of a name he came to carry.
     */
    static boolean nameInQuote(NameRead n, Collection<String> others) {
        String q = flat(n.quote());
        if (q.isEmpty()) return false;
        for (String f : writtenForms(n.name(), n.forms())) if (q.contains(flat(f))) return true;
        if (n.family().isBlank() || n.given().isBlank()) return false;
        String rest = Normalizer.normalize(n.quote(), Normalizer.Form.NFKC);
        List<String> theirs = new ArrayList<>();
        for (String o : others) for (String f : writtenForms(o, List.of())) theirs.add(Normalizer.normalize(f, Normalizer.Form.NFKC));
        theirs.sort((a, b) -> b.length() - a.length());
        for (String f : theirs) rest = rest.replace(f, " | ");
        String r = flat(rest);
        boolean family = false, given = false;
        for (String f : writtenForms(n.family(), List.of())) family |= r.contains(flat(f));
        for (String f : writtenForms(n.given(), List.of())) given |= r.contains(flat(f));
        return family && given;
    }

    // ── a book's index: a relation note beside a name ─────────────────────────────────────────────────────────────────

    // the heading that begins a book's index, and an index entry: "Family, Given (note), 12, 45", at the start of a line or after the pages of
    // the entry before it, with the pages after it and, in a converted book, the sub-entries run on after them
    private static final Pattern INDEX_HEADING = Pattern.compile("(?i)^\\s*(?:index|index of names|name index|人名索引|索引)\\s*$");
    private static final Pattern INDEX_ENTRY = Pattern.compile("(?<=^|[\\d;.]\\s{1,3}|[\\d;.]\\s{0,3}[\\r\\n])\\s*(?<family>\\p{Lu}[\\p{L}\\p{M}'’-]+)\\s*,\\s+(?<given>\\p{Lu}[\\p{L}\\p{M}'’.-]*(?:\\s+\\p{Lu}[\\p{L}\\p{M}'’.-]*)*)\\s*[(（](?<note>[^)）]{1,60})[)）](?<pages>[\\s,;:]*(?:\\d[\\d\\s,;–-]*)?)", Pattern.MULTILINE);

    /** The words of an index note that say the writer's relative, and what each files, the writer first: the writer is a child of a mother, a parent of a son, married to a wife. */
    private static String[] indexRelation(String word) {
        String w = word.toLowerCase(Locale.ROOT).strip();
        String bare = w.replaceAll("^(?:step|half|adoptive|adopted|foster|god)[- ]?", "").replaceAll("[- ]in[- ]law$", "");
        boolean step = w.startsWith("step"), inLaw = w.matches(".*[- ]in[- ]law$"), god = w.startsWith("god"), adoptive = w.matches("^adopt.*"), foster = w.startsWith("foster");
        if (bare.matches("father|mother|parent|父|母")) return adoptive ? new String[]{"adopted-by", "w"} : step ? new String[]{"step-parent-of", "p"} : inLaw ? new String[]{"parent-in-law-of", "p"} : god ? new String[]{"godparent-of", "p"} : foster ? new String[]{"foster-child-of", "w"} : new String[]{"child-of", "w"};
        if (bare.matches("son|daughter|child|息子|娘|長男|次男|三男|長女|次女|三女")) return step || inLaw || god || adoptive || foster ? new String[]{"relative-of", "w"} : new String[]{"parent-of", "w"};
        if (bare.matches("wife|husband|spouse|妻|夫")) return new String[]{"married-to", "w"};
        if (bare.matches("brother|sister|sibling|兄|弟|姉|妹")) return step || inLaw ? new String[]{"relative-of", "w"} : new String[]{"sibling-of", "w"};
        if (bare.matches("grandfather|grandmother|grandparent|grandson|granddaughter|grandchild|uncle|aunt|nephew|niece|cousin|祖父|祖母|孫|叔父|伯父|叔母|伯母|甥|姪|いとこ")) return new String[]{"relative-of", "w"};
        return null;
    }

    /** The parts of a text that are its index: from an index heading to the next heading of another part; a name whose note wrapped onto the next line is joined to it. */
    static List<String> indexParts(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder now = null;
        for (String line : text.split("\\R")) {
            if (INDEX_HEADING.matcher(line).matches()) { if (now != null) out.add(now.toString()); now = new StringBuilder(); continue; }
            if (now == null) continue;
            if (OTHER_PART.matcher(line).matches() && line.strip().length() <= 60) { out.add(now.toString()); now = null; continue; }
            // "Hale, Ann" at the end of a line and "(mother), 12" at the start of the next are one entry
            if (now.length() > 0 && line.matches("^\\s*[(（].*") && now.toString().matches("(?s).*\\p{Lu}[\\p{L}\\p{M}'’-]+\\s*,\\s+\\p{Lu}[\\p{L}\\p{M}'’.-]*\\s*")) {
                now.setLength(now.toString().stripTrailing().length());
                now.append(' ').append(line.strip()).append('\n');
            } else now.append(line).append('\n');
        }
        if (now != null) out.add(now.toString());
        return out;
    }

    /**
     * In a text whose writer is known, an index line "Family, Given (mother)" files the person "Family, Given" in that relation to the writer,
     * from the index line as its words: the writer is a child of a mother, a parent of a son or a daughter, married to a wife or a husband, a
     * brother or sister of a brother; a grandparent, an uncle, a cousin a relative; a step-parent, a parent-in-law, a godparent, an adoptive or
     * a foster parent their own relation. Another given name in the note ("(Ivy; wife)") is another name of the person. A line stands in the
     * index when an index heading comes before it and no other heading between; a note that is no relation word files nothing.
     */
    static void indexRelatives(String text, String writer, Map<String, Person> people, List<Fact> facts, Set<String> known, List<String> wanted, List<String> dropped) {
        if (text == null || writer == null || writer.isBlank() || FamilyQuestions.placeholder(writer)) return;
        int filed = 0;
        for (String part : indexParts(text)) {
            Matcher m = INDEX_ENTRY.matcher(part);
            while (m.find()) {
            String person = m.group("family") + ", " + m.group("given");
            List<String> words = FamilyLinks.relationNotes(person + " (" + m.group("note") + ")");
            String[] rel = null;
            for (String w : words) if ((rel = indexRelation(w)) != null) break;
            if (rel == null || !known.contains(rel[0])) continue;
            if (!wanted.isEmpty()) { String so = flat(KanjiForms.modern(person + " " + writer)); if (wanted.stream().noneMatch(so::contains)) continue; }
            String quote = m.group().strip().replaceAll("\\s+", " ").replaceAll("[\\s,;:]+$", "");
            Fact fact = rel[1].equals("w") ? new Fact(writer, rel[0], person, "", quote) : new Fact(person, rel[0], writer, "", quote);
            boolean held = facts.stream().anyMatch(o -> o.relation().equals(fact.relation()) && (o.subject().equals(fact.subject()) && o.object().equals(fact.object()) || o.subject().equals(fact.object()) && o.object().equals(fact.subject())));
            if (held) continue;
            facts.add(fact);
            filed++;
            List<String> also = new ArrayList<>();
            String[] note = FamilyLinks.note(m.group("note"));
            if (note[0].equals("relation") && !note[1].isBlank()) also.add(note[1]);
            people.merge(person, new Person(person, "", also, m.group("family"), m.group("given")), (a, b) -> new Person(a.name(), a.reading(), union(a.also(), b.also()), a.family().isEmpty() ? b.family() : a.family(), a.given().isEmpty() ? b.given() : a.given()));
            }
        }
        if (filed > 0) dropped.add("The book's index names " + (filed == 1 ? "one relative" : filed + " relatives") + " of " + writer + " by a relation word beside the name, and the library filed each as that relation to " + writer + ".");
    }

    // ── a name the words give to a relative ──────────────────────────────────────────────────────────────────────────

    /** The words that begin a name-owning phrase, and the words that narrow a relation word: "her husband", "their eldest son", "the late father". */
    private static final String NARROW_REL = "(?:(?:only|eldest|elder|oldest|older|younger|youngest|first|second|third|fourth|late|beloved|dear|own|first-born|second-born)\\s+)*";
    private static final String REL_EN = "(?:husband|wife|son|daughter|father|mother|brother|sister|grandson|granddaughter|grandfather|grandmother|uncle|aunt|nephew|niece|cousin|child|children|parents?)";
    private static final String REL_JA = "(?:夫|妻|息子|娘|父|母|兄|弟|姉|妹|長男|次男|三男|四男|長女|次女|三女|四女|末男|末女|祖父|祖母|孫|叔父|伯父|叔母|伯母|甥|姪|従兄弟|従姉妹)";
    private static final String A_NAME = "\\p{Lu}[\\p{L}\\p{M}'’.-]*(?:\\s+\\p{Lu}[\\p{L}\\p{M}'’.-]*)*";

    /** The name of the person the words make a relative of: with the relation word before the name ("Tom Hart's wife Ann", "his son Ned", 森田勇の妻ハル). */
    private static Pattern relBefore(String form) {
        return Pattern.compile("(?i)(?:(?<own>" + A_NAME + ")['’]s\\s+|(?<pron>his|her|their|my|our)\\s+|(?:the|a)\\s+)?" + NARROW_REL + "(?<rel>" + REL_EN + ")\\s*[,:]?\\s*(?:(?:named|called)\\s+)?" + Pattern.quote(form) + "(?![\\p{L}\\p{M}])"
                + "|(?:(?<jown>[\\p{IsHan}\\p{IsKatakana}]{2,})\\s*の\\s*)?(?<![\\p{IsHan}])(?<jrel>" + REL_JA + ")\\s*(?:の|[、・:：])?\\s*" + Pattern.quote(form));
    }

    /** The same, with the relation word after the name ("Ann Hale, his wife"; "Ann Hale, wife of Tom Hart"; ハル（妻）; ハルは勇の妻). */
    private static Pattern relAfter(String form) {
        return Pattern.compile("(?i)" + Pattern.quote(form) + "\\s*[,、]?\\s*[(（]?\\s*(?:(?<pron>his|her|their|my|our)\\s+" + NARROW_REL + "(?<rel>" + REL_EN + ")|(?<own>" + A_NAME + ")['’]s\\s+" + NARROW_REL + "(?<rel2>" + REL_EN + ")"
                + "|(?:the\\s+)?" + NARROW_REL + "(?<rel3>" + REL_EN + ")\\s+of\\s+(?<own2>" + A_NAME + "))(?![\\p{L}\\p{M}])"
                + "|" + Pattern.quote(form) + "\\s*(?:[（(]\\s*(?<jrel>" + REL_JA + ")\\s*[）)]|(?:は|が)\\s*(?<jown>[\\p{IsHan}\\p{IsKatakana}]{2,})\\s*の\\s*(?<jrel2>" + REL_JA + "))");
    }

    /** The relation a relation word makes the named one to its owner: the owner is a parent of a son, a child of a father, married to a wife. */
    private static String relationOfWord(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        if (w.matches("husband|wife|夫|妻")) return "married-to";
        if (w.matches("son|daughter|child|children|息子|娘|長男|次男|三男|四男|長女|次女|三女|四女|末男|末女")) return "parent-of";
        if (w.matches("father|mother|parent|parents|父|母")) return "child-of";
        if (w.matches("brother|sister|兄|弟|姉|妹")) return "sibling-of";
        return "relative-of";
    }

    /** The sex a relation word gives the one it names: a husband, a son, a father is a man; "" where the word says neither. */
    private static String sexOfWord(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        if (w.matches("husband|son|father|brother|grandson|grandfather|uncle|nephew|夫|息子|父|兄|弟|長男|次男|三男|四男|末男|祖父|孫息子|叔父|伯父|甥")) return "male";
        if (w.matches("wife|daughter|mother|sister|granddaughter|grandmother|aunt|niece|妻|娘|母|姉|妹|長女|次女|三女|四女|末女|祖母|叔母|伯母|姪")) return "female";
        return "";
    }

    /** Whether the words name a person: the person's ways of being written, whole, or a word of three letters or more of one of them, or a name in characters that ends one of them. */
    private static boolean writes(String words, List<String> ways) {
        String w = flat(words);
        if (w.isEmpty()) return false;
        for (String way : ways) {
            String f = flat(way);
            if (f.isEmpty()) continue;
            if (f.equals(w) || f.endsWith(w) && FamilyForms.script(way).equals("han") && w.length() >= 2) return true;
            if (FamilyForms.script(way).equals("latin")) for (String x : unaccented(way).toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) if (x.length() >= 3 && x.equals(unaccented(words).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}]", ""))) return true;
            if (FamilyForms.script(way).equals("latin")) for (String x : unaccented(way).toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) for (String y : unaccented(words).toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) if (x.length() >= 3 && x.equals(y)) return true;
        }
        return false;
    }

    /** A person's ways of being written in this read: the name, the other spellings, the reading and the given part. */
    private static List<String> ways(String name, Map<String, Person> people) {
        List<String> out = new ArrayList<>(List.of(name));
        Person p = people.get(name);
        if (p != null) { out.addAll(p.also()); if (!p.reading().isBlank()) out.add(p.reading()); if (!p.given().isBlank()) out.add(p.given()); }
        return out;
    }

    /** The one person of the read the words name, other than {@code not}; null for nobody, or for several. */
    private static String personNamed(String words, Map<String, Person> people, String not) {
        String found = null;
        for (Person p : people.values()) {
            if (p.name().equals(not) || !writes(words, ways(p.name(), people))) continue;
            if (found != null) return null;
            found = p.name();
        }
        return found;
    }

    /** The person of the read written by this name, whole, other than {@code not}; null for nobody. */
    private static String personWritten(String name, Map<String, Person> people, String not) {
        for (Person p : people.values()) if (!p.name().equals(not)) for (String w : ways(p.name(), people)) if (sameWay(w, name)) return p.name();
        return null;
    }

    /**
     * Whether the owner of a relation word is one word that says whose relative only by a family or not at all: a family part the read gives
     * the person or anybody, a word the read's family parts know, a word nobody of the read is written by, or a word of the person's own name
     * whose part is not known. The person's given name alone is the person.
     */
    private static boolean familyAlone(String own, String p, Map<String, Person> people, Set<String> familyParts) {
        String o = own.strip();
        if (o.split("\\s+").length > 1) return false;
        Person me = people.get(p);
        if (me != null && !me.given().isBlank() && FamilyForms.sameForm(me.given(), o)) return false;
        if (FamilyMentions.known(o, familyParts)) return true;
        for (Person x : people.values()) if (!x.family().isBlank() && FamilyForms.sameForm(x.family(), o)) return true;
        if (personNamed(o, people, null) == null) return true;
        return writes(o, ways(p, people)) && (me == null || me.given().isBlank());
    }

    /** Whether the owner of a relation word is one word that writes somebody else of the read too: a given name two people share says whose only by the context. */
    private static boolean ambiguousOwner(String own, String p, Map<String, Person> people) {
        if (own.strip().split("\\s+").length > 1) return false;
        for (Person o : people.values()) if (!o.name().equals(p) && writes(own, ways(o.name(), people))) return true;
        return false;
    }

    /**
     * The names read for this piece ({@code names} from {@code from} on) whose words give the name to a relative of the person, taken off the
     * person and, where the words say whose relative, filed as a fact of that relation instead. The same logic as a sex word's: a relation word
     * right before the name ("her husband Tom Hart", "Ann Hale's son Ned", 妻ハル, 森田勇の夫健二) or right after it ("Tom Hart, her brother";
     * "Ann Hale, wife of Tom Hart"; ハル（妻）) names the relative. It is the relative's, not the person's, when the word's owner is the person
     * themself (their name before the 's, or "my" in the person's own account), when the owner is somebody else the read names and the name
     * shares nothing with the person's, or when the word says the other sex from the one the reading gives the person ("her husband" of a
     * woman). A word with no owner the words name, or whose owner may be the person, decides nothing ("she was known as Ann" stays hers). A
     * name that is another person of this read with facts of their own, or that a fact of this read relates to the person, is that other
     * person's too. Each is said, and a fact made this way is checked as the piece's other facts are.
     */
    static void ofRelatives(List<NameRead> names, int from, Map<String, Person> people, List<Fact> facts, Map<String, Set<String>> sexGiven, Set<String> familyParts, Set<String> known,
                            String teller, Voice voice, String piece, String text, int part, YesNo judge, List<String> dropped) {
        for (int i = names.size() - 1; i >= from; i--) {
            NameRead n = names.get(i);
            String p = n.person();
            List<String> ways = ways(p, people);
            if (writes(n.name(), ways) && !n.name().contains(" ") && !FamilyForms.script(n.name()).equals("han")) continue;   // a given name of the person's own
            String quote = Normalizer.normalize(n.quote(), Normalizer.Form.NFKC);
            String said = null, rel = null, tie = null;
            for (String form : writtenForms(n.name(), n.forms())) {
                String f = Normalizer.normalize(form, Normalizer.Form.NFKC);
                Matcher b = relBefore(f).matcher(quote), a = relAfter(f).matcher(quote);
                String word = null, own = null, pron = null;
                if (b.find()) { word = b.group("rel") != null ? b.group("rel") : b.group("jrel"); own = b.group("own") != null ? b.group("own") : b.group("jown"); pron = b.group("pron"); }
                else if (a.find()) {
                    word = a.group("rel") != null ? a.group("rel") : a.group("rel2") != null ? a.group("rel2") : a.group("rel3") != null ? a.group("rel3") : a.group("jrel") != null ? a.group("jrel") : a.group("jrel2");
                    own = a.group("own") != null ? a.group("own") : a.group("own2") != null ? a.group("own2") : a.group("jown"); pron = a.group("pron");
                }
                if (word == null) continue;
                // a family name alone as the owner ("Endo's son", 森田の妻) says whose relative only by the family: the person may be that relative
                boolean familyOwner = own != null && familyAlone(own, p, people, familyParts);
                if (familyOwner) own = null;
                boolean shares = writes(n.name(), ways);
                boolean self = own != null ? writes(own, ways) && !ambiguousOwner(own, p, people) : pron != null && pron.matches("(?i)my|our") && (p.equals(teller) || isWriter(p, teller));
                String other = own != null && !self ? personNamed(own, people, p) : null;
                String sexOfPerson = sexGiven.getOrDefault(p, Set.of()).size() == 1 ? sexGiven.get(p).iterator().next() : "";
                boolean otherSex = !sexOfWord(word).isEmpty() && !sexOfPerson.isEmpty() && !sexOfWord(word).equals(sexOfPerson);
                String w = word.toLowerCase(Locale.ROOT);
                if (self) { said = "call " + n.name() + " " + p + "'s " + w; rel = relationOfWord(word); tie = p; }
                else if (own != null && !shares) { said = "call " + n.name() + " " + own + "'s " + w; if (other != null) { rel = relationOfWord(word); tie = other; } }
                else if (own == null && otherSex) said = "call " + n.name() + " " + (pron == null ? "a" : pron.toLowerCase(Locale.ROOT)) + " " + w + ", and this text makes " + p + " a " + (sexOfPerson.equals("male") ? "man" : "woman");
                else if (own == null && pron == null && !familyOwner && !shares) said = "call " + n.name() + " a " + w + ", and nothing in them ties that " + w + " to " + p;
                if (said != null) break;
            }
            if (said == null) continue;
            names.remove(i);
            String line = "The name " + n.name() + " of " + p + " " + OF_A_RELATIVE + ": the words given for it (\"" + Acquisitions.compress(n.quote(), 60) + "\") " + said + ".";
            if (tie != null && rel != null && known.contains(rel)) {
                Fact fact = new Fact(tie, rel, n.name(), n.date(), n.quote());
                Fact kept = voice != null ? voiced(fact, voice, piece, text, part, judge, dropped) : fact;
                boolean held = facts.stream().anyMatch(o -> o.relation().equals(kept.relation()) && (o.subject().equals(kept.subject()) && o.object().equals(kept.object()) || o.subject().equals(kept.object()) && o.object().equals(kept.subject())));
                if (!held) { facts.add(kept); line += " The library wrote down \"" + sentence(kept.subject(), kept.relation(), kept.object()) + "\" instead."; }
                people.putIfAbsent(n.name(), new Person(n.name(), "", List.of(), n.family(), n.given()));
            }
            dropped.add(line);
        }
    }

    /**
     * At the end of a read, the names that are somebody else of the same read: a name that is another person of the read, written so in any of
     * their ways, with facts of their own, and not the same person by the read's own pairing; or a name that a fact of the read relates to the
     * person (a husband, a child, a brother). The read's pieces come in order, and the other person's facts may come pieces after the name, so
     * this is done once over the whole read. Each is taken off the person and said.
     */
    static List<NameRead> ofOthers(List<NameRead> names, List<Person> people, List<Fact> facts, List<String> dropped) { return ofOthers(names, people, facts, "", "", dropped); }

    /**
     * The same, and last, a name whose words speak of somebody else ({@link #spokenOf}): a sentence about another person in which the name
     * stands gives it to nobody but its own subject. It stays when the words say the name came (known as, née, 改名) or the read itself pairs the
     * two ways of writing ({@link #paired}), as a book that writes 遠藤健二 and later "Endo's son, Morita Kenji" does.
     */
    static List<NameRead> ofOthers(List<NameRead> names, List<Person> people, List<Fact> facts, String teller, String text, List<String> dropped) {
        Map<String, Person> byName = new LinkedHashMap<>();
        for (Person p : people) byName.put(p.name(), p);
        List<NameRead> out = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            NameRead n = names.get(i);
            String p = n.person(), said = null;
            String other = personWritten(n.name(), byName, p);
            if (other != null && !FamilyForms.sameForm(other, p)) {
                List<String> theirs = ways(other, byName);
                boolean own = facts.stream().anyMatch(f -> theirs.stream().anyMatch(w -> w.equals(f.subject()) || w.equals(f.object())));
                if (own && !pairedApart(p, other, names, i, byName, facts, n)) said = "name " + n.name() + ", who is another person in this text, with facts of their own";
            }
            if (said == null) for (Fact f : facts) {
                if (!personToPerson(f.relation()) || f.subject().equals(f.object())) continue;
                String o = f.subject().equals(p) ? f.object() : f.object().equals(p) ? f.subject() : null;
                if (o != null && ways(o, byName).stream().anyMatch(x -> sameWay(x, n.name()))) { said = "make " + p + " and " + n.name() + " two people (" + sentence(f.subject(), f.relation(), f.object()) + ")"; break; }
            }
            if (said == null && !teller.isEmpty()) {
                Person me = byName.get(p);
                // every way the read writes the person, through the spellings of the spellings: 遠藤健二, also 森田健二, whose spelling is Morita Kenji
                Set<String> allWays = new LinkedHashSet<>(ways(p, byName));
                for (String w : new ArrayList<>(allWays)) if (byName.containsKey(w)) allWays.addAll(ways(w, byName));
                Set<String> not = new LinkedHashSet<>();
                if (allWays.stream().noneMatch(a -> sameWay(a, n.name()))) not.addAll(writtenForms(n.name(), n.forms()));
                boolean change = namesTheChange(n.quote(), writtenForms(n.name(), n.forms())) || !FamilyNameHistory.kind(n.kind()).equals("unknown") && FamilyNameHistory.saysKind(n.quote(), FamilyNameHistory.kind(n.kind()));
                // a name that carries the person's given name, in any script (健二, けんじ, Kenji), is a name of theirs: the words write the person by it
                boolean givenShared = me != null && sharesGivenAcrossScripts(me, n.name(), n.given());
                // a name in another script than the person's is a reading or a romanisation, which the reading rules weigh
                boolean sameScript = FamilyForms.script(n.name()).equals(FamilyForms.script(p));
                if (sameScript && !change && !givenShared && !spokenOf(n.quote(), p, teller, byName, not) && !pairedApart(p, n.name(), names, i, byName, facts, n) && bracketed(text, p, n.name(), nameWords(new Read(people, facts, List.of(), List.of(), List.of(), List.of()))).isEmpty()
                        && bracketed(text, n.name(), p, Set.of()).isEmpty()) {
                    dropped.add("The name " + n.name() + " of " + p + " was left out, because the words given for it (\"" + Acquisitions.compress(n.quote(), 60) + "\") do not write " + p + ", and no word in them stands for " + p + ".");
                    continue;
                }
            }
            if (said == null) { out.add(n); continue; }
            dropped.add("The name " + n.name() + " of " + p + " " + OF_A_RELATIVE + ": the words given for it (\"" + Acquisitions.compress(n.quote(), 60) + "\") " + said + ".");
        }
        return out;
    }

    /** The words that mark a name the reading gave a person and the words give to a relative ({@link #ofRelatives}). */
    public static final String OF_A_RELATIVE = "was not kept as a name of that person";

    /**
     * Whether the read itself gives two of its people as one ({@link #paired}): the other's name among the person's other spellings, or one in
     * the brackets right after the other in the words of a fact or of this name; this very name claim does not count.
     */
    private static boolean pairedApart(String p, String other, List<NameRead> names, int skip, Map<String, Person> people, List<Fact> facts, NameRead n) {
        List<Person> ps = new ArrayList<>(people.values());
        List<NameRead> rest = new ArrayList<>();
        for (int j = 0; j < names.size(); j++) if (j != skip) rest.add(names.get(j));
        Read view = new Read(ps, facts, List.of(), List.of(), rest, List.of());
        if (paired(view, p, other)) return true;
        Set<String> words = nameWords(view);
        return !bracketed(n.quote(), p, other, words).isEmpty() || !bracketed(n.quote(), other, p, words).isEmpty();
    }

    /** The names of the read's people other than this person's own: their names, other spellings and readings, and both sides of the relations. */
    private static List<String> othersThan(NameRead n, Collection<String> names, Map<String, Person> people) {
        Set<String> own = new LinkedHashSet<>(List.of(n.person(), n.name()));
        own.addAll(n.forms());
        Person p = people.get(n.person());
        if (p != null) { own.addAll(p.also()); if (!p.reading().isBlank()) own.add(p.reading()); }
        Set<String> ownFlat = new LinkedHashSet<>();
        for (String o : own) ownFlat.add(flat(o));
        List<String> out = new ArrayList<>();
        for (String x : names) {
            if (x == null || x.isBlank() || ownFlat.contains(flat(x)) || !FamilyMentions.full(x)) continue;
            boolean mine = false;
            for (String o : own) mine |= FamilyForms.sameForm(o, x);
            if (!mine && !out.contains(x)) out.add(x);
        }
        return out;
    }

    /**
     * Words that say a name came to somebody, with the name right after them (became Takahashi Shōji, took the name Hale, née Hale, born Hale,
     * born in 1905 as Endō Kenji; the name may stand in brackets after its other spelling, as in became Takahashi Shōji (髙橋正二)) or before
     * them (髙橋正二と改名, 森田を名乗る). Defaults behind the rule that a narrative's use of a name never dates it: "Born in 1905, Morita Kenji
     * went to school" says nothing of when he came to be called Morita Kenji.
     */
    private static final String CAME_BEFORE = "(?i)\\b(?:became|become|becoming|was named|renamed|known (?:thereafter|afterwards|from then on|since|later) as|changed (?:his|her|their) (?:family )?(?:name|surname) to"
            + "|took the (?:family )?name(?: of)?|taking the (?:family )?name(?: of)?|assumed the (?:family )?name(?: of)?|née|nee|maiden name|birth name|born(?:\\s+(?:in|on|at)\\s+[^,.;:。、]{1,24}?\\s+as|\\s+as)?)\\s*[:,]?\\s*"
            + "(?:[^,.;:。、()（）]{1,40}?\\s*[(（]\\s*)?";
    private static final String CAME_AFTER = "\\s*[)）]?\\s*(?:と|に|を)?\\s*(?:改名|改姓|名乗|称し|称す|襲名|改め|なる|なっ|となり)";

    static boolean namesTheChange(String quote, List<String> forms) { return !changeSpans(quote, forms).isEmpty(); }

    /** Where in the quote words say a name came, each as {start, end}: the words and the name together. */
    static List<int[]> changeSpans(String quote, List<String> forms) {
        String q = Normalizer.normalize(quote == null ? "" : quote, Normalizer.Form.NFKC);
        List<int[]> out = new ArrayList<>();
        for (String f : forms) {
            String w = Pattern.quote(Normalizer.normalize(f, Normalizer.Form.NFKC));
            for (Pattern p : List.of(Pattern.compile(CAME_BEFORE + w), Pattern.compile(w + CAME_AFTER), Pattern.compile("(?:旧姓|本名|通称|雅号|筆名|戒名|法名)\\s*[:：]?\\s*" + w))) {
                Matcher m = p.matcher(q);
                while (m.find()) out.add(new int[]{m.start(), m.end()});
            }
        }
        return out;
    }

    /**
     * A name in Latin letters whose words are the name alone ("Ken Ellis", a signature under a foreword) and that shares no word with any way the
     * read writes the person in Latin letters, or, for the writer, with the writer's name as the text's first pages write it ({@code more}):
     * nothing in the words ties the name to the person, so it is left out, and said. A name the person has no Latin form beside cannot be
     * compared, and stays.
     */
    static boolean nameAlone(NameRead n, Person p, List<String> more, List<String> dropped) {
        if (!FamilyForms.script(n.name()).equals("latin")) return false;
        String rest = flat(n.quote()).replace(flat(n.name()), "");
        if (rest.codePoints().filter(Character::isLetter).count() >= 2) return false;
        Set<String> words = new HashSet<>();
        // the writer's name as the first pages write it is the text's own word; the other spellings the reading gives the writer come from the
        // same reading that may have taken a signature for a name of theirs
        List<String> ways = new ArrayList<>(more);
        if (p != null) ways.addAll(more.isEmpty() ? union(List.of(p.name(), p.reading()), p.also()) : List.of(p.name()));
        for (String w : ways) if (FamilyForms.script(w).equals("latin")) for (String x : unaccented(w).toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) if (x.length() >= 2) words.add(x);
        if (words.isEmpty()) return false;
        for (String x : unaccented(n.name()).toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) if (words.contains(x)) return false;
        dropped.add("The name " + n.name() + " of " + n.person() + " was left out, because the words given for it are the name alone, and nothing in them ties it to " + n.person() + ".");
        return true;
    }

    /** The writer's name as the text's first pages write it, for the writer, the one the text's "I" is; empty for anybody else. */
    private static List<String> writtenAs(String person, String teller, Voice voice) {
        return voice != null && !voice.writtenAs().isBlank() && (person.equals(teller) || isWriter(person, voice.writer())) ? List.of(voice.writtenAs()) : List.of();
    }

    // the words that credit somebody with work on a text: "edited by", "from a translation by", 訳, 編
    private static final String CREDIT_BEFORE = "(?i)(?:(?:edited|introduced|translated|compiled|illustrated|transcribed|annotated|abridged)(?:\\s+and\\s+\\w+)?\\s+by|(?:translation|foreword|introduction|preface|afterword|notes)\\s+by|(?:訳者|編者|翻訳|監修)\\s*[:：]?)\\s*";
    private static final String CREDIT_AFTER = "\\s*(?:訳|編|編集|監修|翻訳)(?![\\p{IsHan}])";

    /** Whether the words credit the name with work on the text, as its editor or its translator: that is somebody else, never another name of the person. */
    static boolean credited(NameRead n) {
        String q = Normalizer.normalize(n.quote(), Normalizer.Form.NFKC);
        for (String f : writtenForms(n.name(), n.forms())) {
            String w = Pattern.quote(Normalizer.normalize(f, Normalizer.Form.NFKC));
            if (Pattern.compile(CREDIT_BEFORE + w).matcher(q).find() || Pattern.compile(w + CREDIT_AFTER).matcher(q).find()) return true;
        }
        return false;
    }

    /** Abbreviations whose full stop ends no sentence. */
    private static final Pattern ABBREVIATION = Pattern.compile("(?i)(?:^|\\s)(?:mr|mrs|ms|dr|st|jr|sr|no|rev|capt|col|gen|lt|\\p{L})$");

    /** A quote cut into its sentences: at 。, ！, ？, ; and at a full stop that follows a word, not an initial (M. Ellis) or a title (Mr.). */
    static List<String> sentences(String quote) {
        String q = Normalizer.normalize(quote == null ? "" : quote, Normalizer.Form.NFKC);
        List<String> out = new ArrayList<>();
        int from = 0;
        Matcher m = Pattern.compile("[。！？!?;；]|\\.(?=\\s|$)").matcher(q);
        while (m.find()) {
            if (m.group().equals(".") && ABBREVIATION.matcher(q.substring(from, m.start())).find()) continue;
            out.add(q.substring(from, m.end()));
            from = m.end();
        }
        if (from < q.length() && !q.substring(from).isBlank()) out.add(q.substring(from));
        return out;
    }

    private static final Pattern A_YEAR = Pattern.compile("(?<!\\d)(1[0-9]{3}|20[0-9]{2})(?!\\d)");

    /**
     * Whether a name's date is kept: a sentence of the quote carries the date's own words (the date as written, or its year) and says the name
     * came, by the kind's own words or by words that say a name came; with those words, the year must be the one written nearest to them ("Born
     * in 1905 as Endō Kenji, he went to school in 1920" dates the name 1905, never 1920). A narrative that calls a person by a name in a year
     * says nothing of when the name came.
     */
    static boolean datedByWords(NameRead n, String kind) {
        String date = n.date();
        if (date.isBlank()) return true;
        List<String> forms = writtenForms(n.name(), n.forms());
        if (date.equalsIgnoreCase("event")) return !kind.equals("unknown") && FamilyNameHistory.saysKind(n.quote(), kind) || namesTheChange(n.quote(), forms);
        FamilyDate d = FamilyDate.parse(date);
        for (String s : sentences(n.quote())) {
            String fs = flat(s);
            boolean words = fs.contains(flat(date)) || (d != null && (s.contains(String.valueOf(d.year())) || (d.written() != null && !flat(d.written()).isEmpty() && fs.contains(flat(d.written())))));
            if (!words) continue;
            if (!kind.equals("unknown") && FamilyNameHistory.saysKind(s, kind)) return true;
            for (int[] span : changeSpans(s, forms)) {
                int best = Integer.MAX_VALUE;
                List<Integer> nearest = new ArrayList<>();
                Matcher y = A_YEAR.matcher(s);
                while (y.find()) {
                    int dist = y.start() >= span[0] && y.start() < span[1] ? 0 : Math.min(Math.abs(y.start() - span[0]), Math.abs(y.start() - span[1]));
                    if (dist < best) { best = dist; nearest.clear(); }
                    if (dist == best) nearest.add(Integer.parseInt(y.group(1)));
                }
                if (nearest.isEmpty() || d != null && nearest.contains(d.year())) return true;
            }
        }
        return false;
    }

    /**
     * A name the model read, kept only as far as the words say it: its words must be in the text (joined words are asked about once, as a
     * table's are); the name must stand in its quote; its kind is kept when the quote says it in words or the judge is sure, else it is "not
     * known yet" with the model's word kept for the question; its date is kept when the quote dates the name's coming. Null, with the reason
     * in {@code dropped}, when the name is left out. A person's own name with nothing more said of it is not a name of its own.
     */
    static NameRead checkName(NameRead n, String text, String whole, Model model, YesNo judge, List<String> dropped) { return checkName(n, text, whole, model, judge, dropped, List.of()); }

    /** The same, where {@code others} are the names of the read's other people, inside which a part of this name does not count ({@link #nameInQuote(NameRead, Collection)}). */
    static NameRead checkName(NameRead n, String text, String whole, Model model, YesNo judge, List<String> dropped, Collection<String> others) { return checkName(n, text, whole, model, judge, dropped, others, null, ""); }

    /**
     * The same, where the quote must also speak of the person the name is given to ({@link #spokenOf}): a sentence about somebody else, in which
     * a name stands, gives that name to nobody but its own subject. {@code people} null skips this.
     */
    static NameRead checkName(NameRead n, String text, String whole, Model model, YesNo judge, List<String> dropped, Collection<String> others, Map<String, Person> people, String teller) {
        String what = "The name " + n.name() + " of " + n.person();
        if (n.quote().isBlank()) { dropped.add(what + " was left out, because it came without the words of the text that say it."); return null; }
        if (!said(whole, n.quote())) {
            String near = stitched(whole, n.quote());
            if (near == null) { dropped.add(what + " was left out, because the library could not find the words in the text that say it. The words it was given were: \"" + Acquisitions.compress(n.quote(), 60) + "\""); return null; }
            if (!sure(model, judge, checkQuestion(n.quote(), n.person() + " carried the name " + n.name()))) { dropped.add(what + " was left out. The words are in the text (\"" + Acquisitions.compress(n.quote(), 60) + "\"), and on a second look they do not say it."); return null; }
        }
        if (!nameInQuote(n, others)) { dropped.add(what + " was left out, because the words given for it (\"" + Acquisitions.compress(n.quote(), 60) + "\") do not have that name in them."); return null; }

        if (credited(n)) { dropped.add(what + " was left out, because the words given for it (\"" + Acquisitions.compress(n.quote(), 60) + "\") name " + n.name() + " as somebody who worked on the text, such as its editor or its translator."); return null; }
        String kind = FamilyNameHistory.kind(n.kind()), said = n.said();
        if (kind.equals("unknown") && !n.kind().isBlank() && said.isBlank()) said = n.kind();
        if (!kind.equals("unknown") && !FamilyNameHistory.saysKind(n.quote(), kind) && !sure(model, judge, checkQuestion(n.quote(), FamilyNameHistory.claimSentence(n.person(), n.name(), kind, "").replaceFirst("\\.$", "")))) {
            if (said.isBlank()) said = n.kind();
            kind = "unknown";
        }
        boolean own = n.name().equals(n.person()) || FamilyForms.sameForm(n.name(), n.person());
        String date = n.date();
        if (!datedByWords(n, kind)) {
            if (!(own && kind.equals("unknown"))) dropped.add("The date " + date + " was not kept as the date " + n.person() + " came to be named " + n.name() + ", because the words given for it (\"" + Acquisitions.compress(n.quote(), 60)
                    + "\") do not say that the name came then. The name is kept without a date.");
            date = "";
        }
        // the person's own name, with nothing said of how it came or when: the person is filed under it already
        if (own && kind.equals("unknown") && date.isBlank()) return null;
        return new NameRead(n.person(), n.name(), n.family(), n.given(), n.forms(), kind, said, date, n.quote());
    }

    /**
     * A family the model read, kept only when the words speak of it as a family (a family word beside its name, or the judge is sure), with
     * its seat only when the seat's words are in the quote. Null, with the reason in {@code dropped}, when it is left out.
     */
    static FamilyRead checkFamily(FamilyRead f, String text, String whole, Model model, YesNo judge, List<String> dropped) { return checkFamily(f, text, whole, model, judge, dropped, Set.of()); }

    /** The same, where {@code personParts} are the family names the read's people carry ({@link #namedAsFamily(String, String, Set)}). */
    static FamilyRead checkFamily(FamilyRead f, String text, String whole, Model model, YesNo judge, List<String> dropped, Set<String> personParts) {
        String name = f.name().isBlank() ? FamilyHouses.familyName(f.written()) : f.name();
        String what = "The " + name + " family";
        if (f.quote().isBlank()) { dropped.add(what + " was left out, because it came without the words of the text that say it."); return null; }
        if (!said(whole, f.quote()) && stitched(whole, f.quote()) == null) { dropped.add(what + " was left out, because the library could not find the words in the text that speak of it. The words it was given were: \"" + Acquisitions.compress(f.quote(), 60) + "\""); return null; }
        if (!FamilyMentions.properName(FamilyHouses.familyName(name))) { dropped.add("A family was left out, because the words given for it (\"" + Acquisitions.compress(f.quote(), 60) + "\") do not name the family."); return null; }
        boolean named = namedAsFamily(f.written().isBlank() ? name : f.written(), f.quote(), personParts) || namedAsFamily(name, f.quote(), personParts);
        if (!named && !sure(model, judge, checkQuestion(f.quote(), "the words speak of the " + name + " family as a family (a house, a line or a 家)"))) {
            dropped.add(what + " was left out, because the words given for it (\"" + Acquisitions.compress(f.quote(), 60) + "\") do not speak of it as a family.");
            return null;
        }
        String seat = !f.seat().isBlank() && flat(f.quote()).contains(flat(f.seat())) ? f.seat() : "";
        return new FamilyRead(name, f.written(), seat, f.quote());
    }

    /**
     * A relation with one party written by one word, which may be a family name alone: the nearest earlier full name of the same piece whose
     * family part is that word is kept in the fact's reading ({@link FamilyMentions#NEAR}), as a candidate for who it is, never as the answer.
     */
    private static Fact nearest(Fact f, String piece, List<Person> people, List<String> names) {
        if (!(personToPerson(f.relation()) || associate(f.relation()))) return f;
        for (String[] side : new String[][]{{f.object(), f.subject()}, {f.subject(), f.object()}}) {
            String w = FamilyMentions.word(side[0]);
            if (w == null || FamilyMentions.full(w) || !(f.detail().containsKey(FamilyMentions.MARKED) || FamilyMentions.possessive(w, f.quote()))) continue;
            String near = FamilyMentions.inDocument(piece, f.quote(), w, people, names, side[1]);
            if (near.isEmpty()) return f;
            Map<String, String> d = new LinkedHashMap<>(f.detail());
            d.put(FamilyMentions.NEAR, near);
            return new Fact(f.subject(), f.relation(), f.object(), f.date(), f.quote(), d);
        }
        return f;
    }

    /**
     * 婿養子 is three facts at once: the adoption by the wife's parent, the marriage to the daughter, and the entry into her family. When a
     * quote says 婿養子 and the read gives one or two of them from it, the others are added from the same words where the read names who:
     * the adoptive parent from the adoption, the wife from the marriage or as the parent's child whom the words call a daughter or a wife, the
     * family from the entry, a family the quote names, or the parent's own family. What cannot be named is left for the family to answer.
     */
    static List<Fact> mukoyoshiHalves(List<Fact> facts, List<NameRead> names, List<FamilyRead> families) { return mukoyoshiHalves(facts, names, families, List.of()); }

    /**
     * The same, with the other spellings the read gives its people, by which a name the words write in another script is found in them. The
     * words are about one person, the 婿養子: the one the read has adopted as 婿養子 or named so; failing that, among the adoptions the read
     * gives without their kind, the one the 婿養子 word stands with by its own people ({@link #mukoyoshiSaidOf}), or else the one of the person
     * the sentence is about, whom it writes only as "he" while no other part of it speaks of an adoption ({@link #onlyAsHe}); failing that the
     * one the read enters into the family as 婿養子; otherwise nobody, and the family is asked. Every other fact of the words
     * stays as the words and the read give it, and an entry as 婿養子 the read gives anybody else (his wife, who was born into the family) is an
     * entry whose way is not stated.
     */
    static List<Fact> mukoyoshiHalves(List<Fact> facts, List<NameRead> names, List<FamilyRead> families, List<Person> people) {
        Map<String, List<String>> alsoOf = new LinkedHashMap<>();
        for (Person p : people) { List<String> a = new ArrayList<>(p.also()); if (!p.reading().isBlank()) a.add(p.reading()); if (!p.given().isBlank()) a.add(p.given()); alsoOf.put(p.name(), a); }
        // the family names the read gives: a word of a person's other spelling that is one of them says nothing of which person it is
        Set<String> familyParts = new LinkedHashSet<>();
        for (Person p : people) if (!p.family().isBlank()) familyParts.add(p.family());
        for (NameRead n : names) if (!n.family().isBlank()) familyParts.add(n.family());
        for (FamilyRead r : families) for (String w : List.of(r.name(), FamilyHouses.familyName(r.written()))) if (!w.isBlank()) familyParts.add(w);
        List<Fact> out = new ArrayList<>(facts);
        Set<String> quotes = new LinkedHashSet<>();
        for (Fact f : facts) if (FamilyNameHistory.saysKind(f.quote(), "mukoyoshi")) quotes.add(f.quote());
        for (NameRead n : names) if (FamilyNameHistory.kind(n.kind()).equals("mukoyoshi") && FamilyNameHistory.saysKind(n.quote(), "mukoyoshi")) quotes.add(n.quote());
        for (String q : quotes) {
            String fq = flat(q);
            Set<String> who = new LinkedHashSet<>();
            for (Fact f : out) if (flat(f.quote()).equals(fq) && f.relation().equals("adopted-by") && "mukoyoshi".equals(f.detail().get("kind"))) who.add(f.subject());
            for (NameRead n : names) if (flat(n.quote()).equals(fq) && FamilyNameHistory.kind(n.kind()).equals("mukoyoshi")) who.add(n.person());
            // failing that, the adoption the read gives without its kind that the 婿養子 word stands with by that adoption's own people
            // ("健二は…森田家の婿養子となり", "…as mukoyōshi (婿養子) of Isamu"), or the adoption of the one the sentence is about, written only as
            // "he" ("In 1932 he married Haru … and entered the family as 婿養子"); failing that, the one the read enters into the family as 婿養子.
            // The words may be about another person of the same sentence ("…as 婿養子 of Morita Isamu, and his brother Masaru was adopted by
            // Takahashi Shōichi"), and then the family is asked
            if (who.isEmpty()) {
                List<Fact> unkinded = new ArrayList<>();
                for (Fact f : out) if (flat(f.quote()).equals(fq) && f.relation().equals("adopted-by") && Set.of("", "unstated").contains(f.detail().getOrDefault("kind", ""))) unkinded.add(f);
                List<Fact> tied = new ArrayList<>(), his = new ArrayList<>();
                for (Fact f : unkinded) if (mukoyoshiSaidOf(q, f, alsoOf, familyParts)) tied.add(f);
                for (Fact f : unkinded) if (onlyAsHe(q, f.subject(), alsoOf, familyParts)) his.add(f);
                if (tied.size() == 1) who.add(tied.get(0).subject());
                else if (tied.isEmpty() && his.size() == 1 && !adoptionApart(q)) who.add(his.get(0).subject());
            }
            if (who.isEmpty()) {
                Set<String> entered = new LinkedHashSet<>();
                for (Fact f : out) if (flat(f.quote()).equals(fq) && f.relation().equals(FamilyHouses.MEMBER) && "mukoyoshi".equals(f.detail().get("how"))) entered.add(f.subject());
                if (entered.size() == 1) who.addAll(entered);
            }
            // an adoption of somebody else in the same words has its kind not stated, so that the 婿養子 word in its quote is never read as its
            // own later on
            for (int i = 0; i < out.size(); i++) {
                Fact f = out.get(i);
                if (flat(f.quote()).equals(fq) && f.relation().equals("adopted-by") && !who.contains(f.subject()) && f.detail().getOrDefault("kind", "").isEmpty()) out.set(i, withDetail(f, "kind", "unstated"));
            }
            if (who.isEmpty()) continue;
            // an entry as 婿養子 the read gives somebody the words are not about: the way they came in is not stated
            for (int i = 0; i < out.size(); i++) {
                Fact f = out.get(i);
                if (flat(f.quote()).equals(fq) && f.relation().equals(FamilyHouses.MEMBER) && "mukoyoshi".equals(f.detail().get("how")) && !who.contains(f.subject())) out.set(i, withDetail(f, "how", "unstated"));
            }
            for (String p : who) {
                String date = "", parent = null, wife = null, family = null;
                for (Fact f : out) if (flat(f.quote()).equals(fq) && f.subject().equals(p) && !f.date().isBlank()) { date = f.date(); break; }
                for (Fact f : out) if (flat(f.quote()).equals(fq) && f.relation().equals("adopted-by") && f.subject().equals(p)) { parent = f.object(); break; }
                for (Fact f : out) if (flat(f.quote()).equals(fq) && f.relation().equals("married-to") && (f.subject().equals(p) || f.object().equals(p))) { wife = f.subject().equals(p) ? f.object() : f.subject(); break; }
                if (wife == null && parent != null) for (Fact f : out) {
                    String child = f.relation().equals("child-of") && f.object().equals(parent) ? f.subject() : f.relation().equals("parent-of") && f.subject().equals(parent) ? f.object() : null;
                    if (child == null || child.equals(p) || who.contains(child) || !namedIn(child, alsoOf.getOrDefault(child, List.of()), fq, parent)) continue;
                    // the wife is the parent's child whom the words call a daughter or a wife: a son is no wife, and a child the words do not say is
                    // a daughter is the family's to answer
                    if (!calledDaughterOrWife(child, alsoOf.getOrDefault(child, List.of()), q, parent)) continue;
                    wife = child; break;
                }
                for (Fact f : out) if (flat(f.quote()).equals(fq) && f.relation().equals(FamilyHouses.MEMBER) && f.subject().equals(p)) { family = f.object(); break; }
                if (family == null) for (FamilyRead r : families) { String w = r.written().isBlank() ? r.name() : r.written(); if (!w.isBlank() && fq.contains(flat(w))) { family = w; break; } }
                if (family == null && parent != null) for (Fact f : out) if (f.relation().equals(FamilyHouses.MEMBER) && f.subject().equals(parent)) { family = f.object(); break; }
                final String d = date, a = parent, w = wife, fam = family;
                for (int i = 0; i < out.size(); i++) {
                    Fact f = out.get(i);
                    if (!flat(f.quote()).equals(fq) || !f.subject().equals(p)) continue;
                    if (f.relation().equals("adopted-by") && Set.of("", "unstated").contains(f.detail().getOrDefault("kind", ""))) out.set(i, withDetail(f, "kind", "mukoyoshi"));
                    if (f.relation().equals(FamilyHouses.MEMBER) && !"head".equals(f.detail().get("role")) && Set.of("", "unstated").contains(f.detail().getOrDefault("how", ""))) out.set(i, withDetail(f, "how", "mukoyoshi"));
                }
                if (a != null && out.stream().noneMatch(f -> f.relation().equals("adopted-by") && f.subject().equals(p) && f.object().equals(a))) out.add(new Fact(p, "adopted-by", a, d, q, Map.of("kind", "mukoyoshi")));
                if (w != null && out.stream().noneMatch(f -> f.relation().equals("married-to") && (f.subject().equals(p) && f.object().equals(w) || f.subject().equals(w) && f.object().equals(p)))) out.add(new Fact(p, "married-to", w, d, q));
                if (fam != null && out.stream().noneMatch(f -> f.relation().equals(FamilyHouses.MEMBER) && f.subject().equals(p) && FamilyHouses.familyName(f.object()).equals(FamilyHouses.familyName(fam)))) out.add(new Fact(p, FamilyHouses.MEMBER, fam, d, q, Map.of("how", "mukoyoshi")));
            }
        }
        return out;
    }

    /**
     * Whether the words say 婿養子 of this adoption: a part of the quote, cut at a full stop, a semicolon or a comma, holds the 婿養子 word and
     * a way of writing the adopted person or the adopter (whole, or a word of the name the other does not share: Isamu of Morita Isamu beside
     * Morita Kenji).
     */
    static boolean mukoyoshiSaidOf(String quote, Fact adoption, Map<String, List<String>> alsoOf) { return mukoyoshiSaidOf(quote, adoption, alsoOf, Set.of()); }

    /**
     * The same, where a word of another spelling the read gives either of the two counts too, when it is neither one of the family names the
     * read gives ({@code familyParts}) nor a word of the other's spellings: Isamu of Morita Isamu, the read's other spelling of 森田勇, beside
     * 森田健二, whom the read also spells Morita Kenji.
     */
    static boolean mukoyoshiSaidOf(String quote, Fact adoption, Map<String, List<String>> alsoOf, Set<String> familyParts) {
        Set<String> shared = new LinkedHashSet<>();
        Set<String> words = new LinkedHashSet<>();
        for (String name : List.of(adoption.subject(), adoption.object())) for (String w : name.split("[\\s,]+")) if (!w.isBlank() && !words.add(flat(w))) shared.add(flat(w));
        List<String> ways = new ArrayList<>();
        for (String name : List.of(adoption.subject(), adoption.object())) {
            ways.addAll(writtenForms(name, alsoOf.getOrDefault(name, List.of())));
            for (String n : nameForms(name)) if (!shared.contains(flat(n))) ways.add(n);
        }
        List<String> a = spellingWords(adoption.subject(), alsoOf, familyParts), b = spellingWords(adoption.object(), alsoOf, familyParts);
        for (String w : a) if (b.stream().noneMatch(x -> flat(x).equals(flat(w)))) ways.add(w);
        for (String w : b) if (a.stream().noneMatch(x -> flat(x).equals(flat(w)))) ways.add(w);
        for (String part : parts(quote)) {
            if (!FamilyNameHistory.saysKind(part, "mukoyoshi")) continue;
            String p = flat(part);
            for (String w : ways) if (flat(w).length() >= 2 && p.contains(flat(w))) return true;
        }
        return false;
    }

    /** A quote cut into its parts at a full stop, a semicolon and a comma, as the 婿養子 word is looked for beside a person. */
    private static String[] parts(String quote) {
        return Normalizer.normalize(quote == null ? "" : quote, Normalizer.Form.NFKC).split("[;；。!?！？]|\\.(?=\\s|$)|,\\s*|、|，");
    }

    /**
     * The words of the other spellings the read gives a person that are neither a family name the read gives nor the whole spelling: Isamu of
     * Morita Isamu, けんじ of もりた けんじ, 健二 of 遠藤健二. A family name says nothing of which of its bearers the words are about.
     */
    private static List<String> spellingWords(String name, Map<String, List<String>> alsoOf, Set<String> familyParts) {
        List<String> out = new ArrayList<>();
        for (String x : alsoOf.getOrDefault(name, List.of()))
            for (String n : nameForms(x)) {
                if (n.equals(x) || out.contains(n)) continue;
                boolean jp = FamilyForms.japanese(n) || FamilyForms.japanese(name);
                if (familyParts.stream().noneMatch(fp -> FamilyForms.sameForm(fp, n, jp))) out.add(n);
            }
        return out;
    }

    /** "He", as a sentence writes the man it is about, in the languages the reader meets most. */
    private static final Pattern HE = Pattern.compile("(?i)(?<!\\p{L})he(?!\\p{L})|彼(?!女|ら|等)");

    /**
     * Whether the words write this person only as "he": the word stands in them, and no way of writing him does, whole or by a word of it that
     * is no family name (Kenji of Morita Kenji, 健二 of 森田健二). A sentence that writes a man only as "he" is about him, the man the read gave
     * for its facts; one that names the man is not about him through its "he".
     */
    static boolean onlyAsHe(String quote, String person, Map<String, List<String>> alsoOf, Set<String> familyParts) {
        String q = Normalizer.normalize(quote == null ? "" : quote, Normalizer.Form.NFKC);
        if (!HE.matcher(q).find()) return false;
        String fq = flat(q);
        List<String> ways = new ArrayList<>(writtenForms(person, alsoOf.getOrDefault(person, List.of())));
        for (String n : nameForms(person)) if (!n.equals(person) && familyParts.stream().noneMatch(fp -> FamilyForms.sameForm(fp, n, true))) ways.add(n);
        ways.addAll(spellingWords(person, alsoOf, familyParts));
        for (String w : ways) if (flat(w).length() >= 2 && fq.contains(flat(w))) return false;
        return true;
    }

    /** Whether a part of the quote without the 婿養子 word speaks of an adoption: the sentence tells of two adoptions, and which one the read's is, is the family's to answer. */
    static boolean adoptionApart(String quote) {
        for (String part : parts(quote)) if (!FamilyNameHistory.saysKind(part, "mukoyoshi") && FamilyNameHistory.saysKind(part, "adoptive")) return true;
        return false;
    }

    /** The words a daughter or a wife is called by, in the languages the reader meets most. */
    private static final String DAUGHTER_OR_WIFE = "daughter|wife|娘|長女|次女|二女|三女|四女|末女|妻|嫁";

    /**
     * Whether the words call this person a daughter or a wife: the word stands right beside one of the ways the person is written ("daughter
     * Haru", "Haru, the only daughter", 長女ハル, ハル（娘）), and not beside the parent alone.
     */
    static boolean calledDaughterOrWife(String name, List<String> also, String quote, String parent) {
        String q = Normalizer.normalize(quote == null ? "" : quote, Normalizer.Form.NFKC);
        Set<String> ways = new LinkedHashSet<>(writtenForms(name, also));
        Set<String> shared = new LinkedHashSet<>();
        for (String w : (parent == null ? "" : parent).split("[\\s,]+")) if (!w.isBlank()) shared.add(flat(w));
        for (String x : union(List.of(name), also)) for (String n : nameForms(x)) if (!n.equals(x) && !shared.contains(flat(n)) && !(parent != null && KanjiForms.modern(parent).startsWith(KanjiForms.modern(n)))) ways.addAll(writtenForms(n, List.of()));
        for (String way : ways) {
            if (flat(way).length() < 2 && FamilyForms.script(way).equals("latin")) continue;
            String w = Pattern.quote(Normalizer.normalize(way, Normalizer.Form.NFKC));
            if (Pattern.compile("(?i)(?:" + DAUGHTER_OR_WIFE + ")\\s*[:：、,]?\\s*(?:(?:named|called)\\s+)?" + w).matcher(q).find()) return true;
            if (Pattern.compile("(?i)" + w + "(?:\\s*[(（][^)）]{1,20}[)）])?\\s*[,、]?\\s*(?:the\\s+|his\\s+|her\\s+)?(?:(?:only|eldest|elder|oldest|younger|youngest|first|second|third)\\s+)?(?:" + DAUGHTER_OR_WIFE + ")").matcher(q).find()) return true;
            if (Pattern.compile(w + "\\s*[(（]\\s*(?:" + DAUGHTER_OR_WIFE + ")\\s*[)）]").matcher(q).find()) return true;
        }
        return false;
    }

    /**
     * Whether a person's name stands in the flattened words: whole, in any of the spellings the read gives (the romaji of kana among them),
     * or by a part of it that is not the parent's too (the given name: 森田ハル's ハル, Morita Haru's Haru beside Morita Isamu). A family part
     * alone, which a household shares, does not count.
     */
    private static boolean namedIn(String name, List<String> also, String flatWords, String parent) {
        Set<String> ways = new LinkedHashSet<>(writtenForms(name, also));
        Set<String> shared = new LinkedHashSet<>();
        for (String w : (parent == null ? "" : parent).split("[\\s,]+")) if (!w.isBlank()) shared.add(flat(w));
        for (String x : union(List.of(name), also)) for (String n : nameForms(x)) if (!n.equals(x) && !shared.contains(flat(n)) && !(parent != null && KanjiForms.modern(parent).startsWith(KanjiForms.modern(n)))) ways.addAll(writtenForms(n, List.of()));
        for (String w : ways) if (flat(w).length() >= 2 && flatWords.contains(flat(w))) return true;
        return false;
    }

    private static Fact withDetail(Fact f, String key, String value) {
        Map<String, String> d = new LinkedHashMap<>(f.detail());
        d.put(key, value);
        return new Fact(f.subject(), f.relation(), f.object(), f.date(), f.quote(), d);
    }

    private static final Pattern ABSENT = Pattern.compile("^[(（]\\s*(unknown|blank)\\s*(?:[:：]\\s*(.*?))?\\s*[)）]$", Pattern.CASE_INSENSITIVE);
    /** The words with which a record says it does not know. */
    static final Pattern UNKNOWN_WORDS = Pattern.compile("不詳|不明|未詳|判読|記載なし|空欄|(?i:unknown|not known|not stated|not given|not named|illegible|blank)");

    /** "(unknown: 父 不詳)" → "父 不詳", "(unknown)" → "", "(blank)" → "blank"; null for a name. */
    static String absence(String object) {
        Matcher m = ABSENT.matcher(object == null ? "" : object.strip());
        if (!m.find()) return null;
        return m.group(1).equalsIgnoreCase("blank") ? "blank" : m.group(2) == null ? "" : m.group(2).strip();
    }

    /**
     * A parent or a spouse the record says it does not know, kept as a described person that is never searched for: "森田勇's father
     * (unknown: 父 不詳)". Kept only when the words are in the text and say so themselves (不詳, unknown), or, for an empty column,
     * when a line of the text has the heading with nothing in its cell. Null, with the reason in {@code dropped}, otherwise.
     */
    static Fact absent(Fact fact, String absent, String text, String whole, List<String> dropped) {
        String rel = fact.relation();
        if (!rel.equals("child-of") && !rel.equals("married-to") && !rel.equals("adopted-by")) return null;
        boolean blank = absent.equals("blank");
        boolean holds = blank ? blankCell(text, fact.quote(), rel) : (said(whole, fact.quote()) || stitched(whole, fact.quote()) != null) && UNKNOWN_WORDS.matcher(fact.quote()).find();
        String what = rel.equals("married-to") ? "husband or wife" : rel.equals("adopted-by") ? "adoptive parent" : "parent";
        if (!holds) {
            dropped.add("That " + fact.subject() + "'s " + what + " is not known was left out, because the library could not find words in the text that say so (\"" + Acquisitions.compress(fact.quote(), 60) + "\").");
            return null;
        }
        String words = blank ? "left blank in the record" : absent.isEmpty() ? Acquisitions.compress(fact.quote(), 40) : absent;
        String cue = (words + " " + fact.quote()).toLowerCase(Locale.ROOT);
        String role = rel.equals("married-to") ? "husband or wife" : rel.equals("adopted-by") ? "parent by adoption" : cue.contains("father") || cue.contains("父") ? "father" : cue.contains("mother") || cue.contains("母") ? "mother" : "parent";
        return new Fact(fact.subject(), rel, fact.subject() + "'s " + role + " (unknown: " + words + ")", fact.date(), fact.quote(), fact.detail());
    }

    private static final Map<String, String> HEADINGS = Map.of("child-of", "父|母|父親|母親|father|mother|parents?", "married-to", "夫|妻|配偶者|husband|wife|spouse", "adopted-by", "養父|養母|adoptive (?:father|mother|parents?)");

    /** Whether a line of the text holding the quote has the relation's heading followed by an empty cell ("父 | |", "Father: "). */
    static boolean blankCell(String text, String quote, String relation) {
        String q = Normalizer.normalize(quote == null ? "" : quote, Normalizer.Form.NFKC).replaceAll("\\s+", "");
        if (q.isEmpty()) return false;
        Pattern empty = Pattern.compile("(?i)(?:^|[|:：])(?:" + HEADINGS.get(relation) + ")[|:：](?:\\||$)");
        for (String line : Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).split("\\R")) {
            String l = line.replaceAll("\\s+", "");
            if (l.contains(q) && empty.matcher(l).find()) return true;
        }
        return false;
    }

    static final Set<String> VITAL = Set.of("died-on", "died-in", "buried-in", "born-on", "born-in", "baptised-on", "baptised-in");

    /**
     * A record's birth, death or burial belongs to the person the record is about. When the same words also name somebody as its
     * informant, witness or godparent, a vital event read for that somebody is the model taking the wrong person, and is left out:
     * a living informant counted dead would be researched as dead.
     */
    static List<Fact> principal(List<Fact> facts, List<String> dropped) {
        List<Fact> out = new ArrayList<>();
        for (Fact f : facts) {
            boolean named = VITAL.contains(f.relation()) && facts.stream().anyMatch(a -> associate(a.relation()) && a.subject().equals(f.subject()) && !a.object().equals(f.subject())
                    && !flat(a.quote()).isEmpty() && (flat(a.quote()).contains(flat(f.quote())) || flat(f.quote()).contains(flat(a.quote()))));
            if (!named) { out.add(f); continue; }
            dropped.add("\"" + sentence(f.subject(), f.relation(), f.object()) + "\" was left out. The words it was read from (\"" + Acquisitions.compress(f.quote(), 60) + "\") name " + f.subject()
                    + " as the person who reported the event or who was present, so the event belongs to the person the record is about.");
        }
        return out;
    }

    /**
     * What the model read in one piece. A piece it could not answer for is read again as two halves, which a long table or a long list needs.
     * A model that gave no answer at all ({@link Unanswered}) reads no half either: the read of the text ends there.
     */
    private static List<JsonNode> answers(String piece, String teller, String source, List<Vocabulary.Term> relations, Model model, List<String> dropped, int halvings) {
        String raw = model.answer(prompt(piece, teller, relations, source));
        int a = raw == null ? -1 : raw.indexOf('{'), b = raw == null ? -1 : raw.lastIndexOf('}');
        if (a >= 0 && b > a) { try { return List.of(J.readTree(raw.substring(a, b + 1))); } catch (Exception notJson) { } }
        List<JsonNode> out = new ArrayList<>();
        int cut = piece.lastIndexOf('\n', piece.length() / 2 + piece.length() / 8);
        if (halvings > 0 && piece.length() > 800 && cut > piece.length() / 8) {
            out.addAll(answers(piece.substring(0, cut).strip(), teller, source, relations, model, dropped, halvings - 1));
            out.addAll(answers(piece.substring(cut).strip(), teller, source, relations, model, dropped, halvings - 1));
        } else dropped.add("One part of the text could not be read. It begins with: \"" + Acquisitions.compress(piece, 50) + "\". Reading the same file again usually fixes this.");
        return out;
    }

    /** The relation the model meant: its own slug, or the relation that lists the model's word as another way to say it (brother-of, cousin of). */
    static String relationOf(String given, List<Vocabulary.Term> relations) {
        String g = given.toLowerCase(Locale.ROOT).strip();
        for (Vocabulary.Term t : relations) if (t.slug().equals(g)) return g;
        String words = g.replace('-', ' ').replace('_', ' ');
        for (Vocabulary.Term t : relations) for (String alias : t.also()) if (alias.equalsIgnoreCase(words)) return t.slug();
        return g;
    }

    /** Whether the quote stands within one line of the text, spaces aside. */
    static boolean onOneLine(String text, String quote) {
        String q = Normalizer.normalize(quote, Normalizer.Form.NFKC).replaceAll("\\s+", "");
        if (q.isEmpty()) return false;
        for (String line : Normalizer.normalize(text, Normalizer.Form.NFKC).split("\\R")) if (line.replaceAll("\\s+", "").replace("|", "").contains(q.replace("|", "")) && !line.strip().endsWith("|")) return true;
        return false;
    }

    static final int STITCH_SPAN = 400;
    /** The share of a quote's pieces that must stand in the text: the rest may be letters a scan misread. */
    static final double STITCH_SHARE = 0.8;

    /**
     * A quote joined from pieces of the text: every piece is in the text, and all of them within one short stretch of it.
     * Returns that stretch, or null. The pieces are what stands between the quote's punctuation and spaces.
     */
    static String stitched(String wholeFlat, String quote) {
        List<String> parts = new ArrayList<>();
        for (String part : Normalizer.normalize(quote == null ? "" : quote, Normalizer.Form.NFKC).split("[\\s\\p{Punct}「」『』、。“”‘’]+")) { String f = flat(part); if (f.length() >= 2) parts.add(f); }
        if (parts.size() < 2) return null;
        // a scanned page's text has letters misread here and there (internlllent, 1 942, Toshilnichi): a word or two of a quote that the
        // text does not carry does not make the quote a fabrication. Enough of it must be there, close together, and the first piece exactly
        int need = Math.max(2, (int) Math.ceil(parts.size() * STITCH_SHARE));
        for (String first : parts.subList(0, Math.min(3, parts.size()))) {
            for (int at = wholeFlat.indexOf(first); at >= 0; at = wholeFlat.indexOf(first, at + 1)) {
                int from = Math.max(0, at - STITCH_SPAN), to = Math.min(wholeFlat.length(), at + STITCH_SPAN);
                String window = wholeFlat.substring(from, to);
                if (parts.stream().filter(window::contains).count() >= need) return window;
            }
        }
        return null;
    }

    /**
     * The labels a table or an infobox puts by a name to say how the person is related, in the languages the reader meets most. A label
     * before the name ("父: 森田") or after it in brackets ("森田 （父）") says the relation by itself, and the reader keeps the fact without
     * asking. An office's predecessor or successor (先代, 次代) is not a relation, and is left to the question.
     */
    static final Pattern KIN_LABEL = Pattern.compile("(?:^|[\\s（(・:：])(父|母|妻|夫|配偶者|子|息子|娘|長男|次男|二男|三男|四男|五男|長女|次女|二女|三女|四女|末男|末女|末子|孫|曾孫|曽孫|玄孫|来孫|兄|弟|姉|妹|養父|養母|養子|養女|祖父|祖母|曾祖父|曾祖母|叔父|伯父|叔母|伯母|甥|姪|従兄弟|従姉妹|義父|義母|義兄|義弟|義姉|義妹|"
            + "father|mother|wife|husband|spouse|son|daughter|children|child|brother|sister|grandfather|grandmother|grandson|granddaughter|great-grandson|great-granddaughter|parents|uncle|aunt|nephew|niece|cousin|father-in-law|mother-in-law|son-in-law|daughter-in-law|brother-in-law|sister-in-law|stepfather|stepmother|stepson|stepdaughter)(?:[\\s）):：・、]|$)", Pattern.CASE_INSENSITIVE);

    static boolean labelled(String quote) { return quote != null && KIN_LABEL.matcher(quote).find(); }

    private static final Set<String> PARENT_WORDS = Set.of("父", "母", "子", "息子", "娘", "長男", "次男", "二男", "三男", "四男", "五男", "長女", "次女", "二女", "三女", "四女", "末男", "末女", "末子",
            "father", "mother", "son", "daughter", "children", "child", "parents");
    private static final Set<String> ADOPTION_WORDS = Set.of("養父", "養母", "養子", "養女");
    private static final Set<String> SPOUSE_WORDS = Set.of("妻", "夫", "配偶者", "wife", "husband", "spouse");
    private static final Set<String> SIBLING_WORDS = Set.of("兄", "弟", "姉", "妹", "brother", "sister");
    private static final Set<String> IN_LAW_WORDS = Set.of("義父", "義母", "舅", "姑", "father-in-law", "mother-in-law");

    /** Whether a kinship label is of the kind the relation is: a parent or child word for a parent, a spouse word for a marriage, any other kin word for a relative. */
    static boolean labelFits(String label, String relation) {
        String w = label.toLowerCase(Locale.ROOT);
        boolean parent = PARENT_WORDS.contains(w), adoption = ADOPTION_WORDS.contains(w), spouse = SPOUSE_WORDS.contains(w), sibling = SIBLING_WORDS.contains(w);
        return switch (relation) {
            case "parent-of", "child-of" -> parent || adoption;
            case "adopted-by" -> adoption;
            case "married-to" -> spouse;
            case "sibling-of" -> sibling;
            case "relative-of" -> !parent && !adoption && !spouse && !sibling;
            case "parent-in-law-of" -> IN_LAW_WORDS.contains(w);
            default -> false;
        };
    }

    private static final Pattern TWO_CAPITALS = Pattern.compile("\\p{Lu}[\\p{Ll}'’]+(?:[ \\t]+\\p{Lu}[\\p{Ll}'’]+)+|[(（]\\s*\\p{Lu}[\\p{Ll}'’]+\\s*[)）]");

    /**
     * Whether a kinship label in the quote says this relation by itself, without the judge: a label of the right kind stands next to one of
     * the two names, and the quote names nobody else and does not speak as I or we. "my mother (Morita Yuri) and (Endo Shin)'s uncle" has a
     * mother next to a name and still says nothing certain about whose mother, so the judge decides it.
     */
    static boolean labelSays(Fact fact) {
        String q = fact.quote() == null ? "" : Normalizer.normalize(fact.quote(), Normalizer.Form.NFKC).replaceAll("\\s+", " ");
        if (q.isEmpty() || FIRST_PERSON.matcher(q).find()) return false;
        List<String> forms = new ArrayList<>();
        for (String name : List.of(fact.subject(), fact.object())) forms.addAll(nameForms(name));
        // somebody else named in the quote: two capitalised words, or one in brackets, that belong to neither name
        String rest = q;
        for (String f : forms.stream().sorted((a, b) -> b.length() - a.length()).toList()) rest = rest.replace(f, " ");
        Matcher other = TWO_CAPITALS.matcher(rest);
        while (other.find()) if (!KIN_LABEL.matcher(" " + other.group().replaceAll("[(（)）]", "").strip() + " ").matches()) return false;
        Matcher m = KIN_LABEL.matcher(q);
        int from = 0;
        while (from < q.length() && m.find(from)) {
            String label = m.group(1);
            int ls = m.start(1), le = m.end(1);
            from = Math.max(le, m.start() + 1);
            if (!labelFits(label, fact.relation())) continue;
            for (String f : forms) for (int at = q.indexOf(f); at >= 0; at = q.indexOf(f, at + 1)) {
                String between = at >= le ? q.substring(le, at) : at + f.length() <= ls ? q.substring(at + f.length(), ls) : null;
                if (between != null && between.length() <= 4 && between.matches("[\\s:：|｜（(）)・,、\\-]*")) return true;
            }
        }
        return false;
    }

    /** The ways a name stands in a quote: whole, in modern characters, a Japanese name's given part (正一 of 髙橋正一), and each word of three letters or more. */
    static List<String> nameForms(String name) {
        List<String> out = new ArrayList<>();
        String n = name == null ? "" : name.replaceAll("\\s*[(（][^)）]*[)）]\\s*$", "").strip();
        if (n.isEmpty()) return out;
        out.add(n);
        String modern = KanjiForms.modern(n);
        if (!modern.equals(n)) out.add(modern);
        boolean cjk = n.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
        String joined = n.replaceAll("\\s+", "");
        if (cjk && joined.codePointCount(0, joined.length()) >= 3) out.add(joined.substring(joined.offsetByCodePoints(0, 2)));
        if (!cjk) for (String w : n.split("[\\s,]+")) if (w.length() >= 3) out.add(w);
        return out;
    }

    /**
     * The question about a quote, for the judge or for the one-word answer. The quote is from a page about somebody, and a line of that
     * page's own timeline ("1909 entered the university, married Haru") does not repeat the subject's name: the question says whose page.
     */
    static String question(Fact fact, String means, String near) {
        boolean table = near == null || near.isEmpty() || !fact.quote().contains(fact.subject()) && fact.quote().length() < 40;
        String about = fact.quote().contains(fact.subject()) || fact.quote().contains(fact.object()) && !fact.quote().contains(fact.subject()) ? " The page is about " + fact.subject() + ", and a line of it that names nobody else is about " + fact.subject() + "." : "";
        return "A reader took these words from a page about " + fact.subject() + (table ? ", from a table or an infobox" : "") + ": \"" + fact.quote() + "\".\nThe reader concluded: " + fact.subject() + " " + means + " " + fact.object() + "."
                + about + "\nDo the quoted words say that? A table label such as spouse, children, father or mother next to a name says it, before the name or after it in brackets; so does a sentence of the page's own timeline or text that says it. "
                + "A label for an office, a monarch, a predecessor or successor in a post (先代, 次代), a classmate or a colleague does not say a family relation. The page may be a scan whose letters were misread: read the words as the words they were.";
    }

    /** One yes-or-no question to the model: do these words, as they stand in the text, say the relation? A quote that carries a kinship label says it by itself. */
    private static boolean confirms(Model model, YesNo judge, Fact fact, List<Vocabulary.Term> relations, String near) {
        if (labelSays(fact)) return true;
        if (judge != null) {
            // a typed yes/no with its probability: measured on thirty labelled table quotes, 29 right against the one-word answer's 27, and
            // never a reply that is neither. Below the margin the judge is not sure, and the fact is left out, as before
            String means = fact.relation();
            for (Vocabulary.Term t : relations) if (t.slug().equals(fact.relation())) means = t.description();
            double p = judge.pYes(question(fact, means, near));
            return p >= 0.5 + Judge.SURE / 2;
        }
        String means = fact.relation();
        for (Vocabulary.Term t : relations) if (t.slug().equals(fact.relation())) means = t.description();
        String raw = model.answer(question(fact, means, near) + "\nAnswer with one word: yes or no.");
        return raw != null && raw.strip().toLowerCase(Locale.ROOT).replaceAll("(?s).*</think>", "").strip().startsWith("yes");
    }

    /**
     * A family relation read from a book, in words that speak as I or we about the book's named writer. The machine flags it; the judge
     * then reads the passage around the words and says whether the writer speaks in her own voice. Only a sure yes keeps the writer: an
     * interview, a letter or a memoir the book quotes speaks for somebody else, who is filed as "the speaker in <file>, part N" and never
     * searched for. With no judge, the same.
     */
    static Fact voiced(Fact fact, Voice voice, String piece, int part, YesNo judge, List<String> dropped) { return voiced(fact, voice, piece, piece, part, judge, dropped); }

    /**
     * The same, where {@code text} is the whole text the piece is part of. Words that stand in a part the text marks as somebody else's are
     * never the writer's, whatever the relation: a translator's own note (Translator's Note, 訳者あとがき) is the translator's, and a part an
     * editor's heading marks ("with Editor's and Translator's Remarks") is filed about a stand-in. Only a family relation goes to the judge.
     */
    static Fact voiced(Fact fact, Voice voice, String piece, String text, int part, YesNo judge, List<String> dropped) {
        boolean subject = isWriter(fact.subject(), voice.writer()), object = isWriter(fact.object(), voice.writer());
        if (!subject && !object) return fact;
        String whose = partOf(text, fact.quote());
        // in a part that is somebody else's, words cut from a sentence that speaks as I ("…, and then on to further studies in New York") speak as I too
        if (!FIRST_PERSON.matcher(fact.quote()).find() && (whose.isEmpty() || !FIRST_PERSON.matcher(sentenceOf(text, fact.quote())).find())) return fact;
        // words in a part the text marks as the translator's own (a translator's note, 訳者あとがき) are the translator's
        if (whose.equals("translator") && !voice.translator().isBlank()) {
            Fact theirs = new Fact(subject ? voice.translator() : fact.subject(), fact.relation(), object ? voice.translator() : fact.object(), fact.date(), fact.quote(), fact.detail());
            dropped.add("In " + voice.where() + ", the words \"" + Acquisitions.compress(fact.quote(), 90) + "\" stand in the translator's own part of the text, so the library wrote down \""
                    + sentence(theirs.subject(), theirs.relation(), theirs.object()) + "\" instead of \"" + sentence(fact.subject(), fact.relation(), fact.object()) + "\".");
            return theirs;
        }
        boolean elsewhere = !whose.isEmpty();
        if (!elsewhere && !personToPerson(fact.relation())) return fact;
        String passage = around(piece, fact.quote(), 300);
        if (!elsewhere && judge != null && judge.pYes(voiceQuestion(voice, fact.quote(), passage)) >= 0.5 + Judge.SURE / 2) return fact;
        String speaker = "the speaker in " + voice.where() + ", part " + part;
        Fact moved = new Fact(subject ? speaker : fact.subject(), fact.relation(), object ? speaker : fact.object(), fact.date(), fact.quote(), fact.detail());
        dropped.add("In " + voice.where() + ", the words \"" + Acquisitions.compress(fact.quote(), 90) + "\" speak as \"I\" or \"we\"" + (elsewhere
                ? ", and they stand in a part the text marks as an editor's or a translator's, not " + voice.writer() + "'s."
                : ". They may be somebody the book quotes, for example in an interview or a letter, and not " + voice.writer() + ".") + " So the library wrote down \"" + sentence(moved.subject(), moved.relation(), moved.object()) + "\" instead of \"" + sentence(fact.subject(), fact.relation(), fact.object()) + "\"."
                + " If these are " + voice.writer() + "'s own words, this command makes the two one person: researchzosho graph merge \"" + speaker + "\" \"" + voice.writer() + "\"");
        return moved;
    }

    /**
     * A parent and a child the reading gives the other way round from its own words: "My parents, Ruth and Tom" makes Ruth a parent of the one
     * who speaks, never a child. The words win ({@link FamilyKin#parentByWords}): the fact is filed the way they say it, and the read says so.
     */
    static Fact directedByWords(Fact f, String teller, Map<String, Person> people, List<String> said) {
        if (!f.relation().equals("parent-of") && !f.relation().equals("child-of")) return f;
        boolean down = f.relation().equals("parent-of");
        String parent = down ? f.subject() : f.object(), child = down ? f.object() : f.subject();
        if (!FamilyKin.parentByWords(f.quote(), waysOf(parent, people), waysOf(child, people), teller).equals("b")) return f;
        Fact turned = new Fact(f.object(), f.relation(), f.subject(), f.date(), f.quote(), f.detail());
        said.add("\"" + sentence(f.subject(), f.relation(), f.object()) + "\" " + TURNED + " \"" + sentence(turned.subject(), turned.relation(), turned.object())
                + "\", because the words given for it (\"" + Acquisitions.compress(f.quote(), 60) + "\") say it that way.");
        return turned;
    }

    /** The words that begin the sentence of a fact filed the other way round from the reading ({@link #directedByWords}). */
    public static final String TURNED = "was filed the other way round, as";

    // a table's or an infobox's line: a kin label at its start, after a heading, or beside a colon or a bar, as "父：森田勇", "長男 正一", "Spouse: Ann Hale"
    private static final Pattern TABLE_LABEL = Pattern.compile("^\\s*(?:[^:：|｜]{0,24}[:：|｜]\\s*)?(?:父|母|妻|夫|配偶者|子女|子|息子|娘|長男|次男|二男|三男|四男|五男|長女|次女|二女|三女|四女|末男|末女|末子|男|女|孫|兄|弟|姉|妹|養父|養母|養子|養女|継子|祖父|祖母|親族|"
            + "(?i:father|mother|wife|husband|spouse|son|daughter|children|child|brother|sister|parents))(?:\\s*[:：・|｜]|\\s+\\S|\\s*[（(]|$)");

    /** Whether the words are a table's or an infobox's line, which names the page's subject nowhere: a kin label at its start or beside a colon. */
    static boolean tableLabel(String quote) {
        String q = Normalizer.normalize(quote == null ? "" : quote, Normalizer.Form.NFKC);
        return TABLE_LABEL.matcher(q).find() || q.contains("|") || (q.contains(":") || q.contains("：")) && q.length() < 60;   // NFKC writes the full-width colon as ":"
    }

    private static String leftOutWords(Fact f, String why) {
        return "\"" + sentence(f.subject(), f.relation(), f.object()) + "\" was left out, because the words given for it (\"" + Acquisitions.compress(f.quote(), 60) + "\") " + why + ".";
    }

    // a word that stands for a person in a sentence about them: she, he, her, his, 彼, 彼女; "their" for a couple
    private static final Pattern THIRD_PERSON = Pattern.compile("(?i)(?<![\\p{L}])(?:she|he|her|hers|his|him|their)(?![\\p{L}])|彼女|彼(?![女岸方ら等])");

    /**
     * Whether the words speak of a person: one of the person's ways of being written stands in them (the name, a spelling, the reading, the given
     * part, a word of three letters or more of the name), or the person is written by relation words the quote carries, or the person is the
     * account's own I and the words say I or my, or a she, he, her or his stands in them with nobody else named before it. {@code not}: ways
     * that do not count, such as the very name a claim gives the person.
     */
    static boolean spokenOf(String quote, String party, String teller, Map<String, Person> people, Set<String> not) { return spokenOf(quote, party, teller, people, not, false); }

    /**
     * The same; {@code familyCounts}: whether the family part of the person's name alone counts as writing them, as it does for a parent ("born
     * into the Endo family" names a parent of that family, whom the library then describes) and not for a name (a sentence about the Hale
     * children says nothing of Kimie Hale).
     */
    static boolean spokenOf(String quote, String party, String teller, Map<String, Person> people, Set<String> not, boolean familyCounts) {
        String q = Normalizer.normalize(quote == null ? "" : quote, Normalizer.Form.NFKC), fq = flat(q);
        if (party == null || party.isBlank()) return false;
        Set<String> skip = new HashSet<>();
        for (String x : not) skip.add(flat(x));
        List<String> ways = new ArrayList<>(waysOf(party, people == null ? Map.of() : people));
        // a word of the name, or of a spelling of it, when no other person of the read shares it: a family name alone writes nobody in particular
        Person me = people == null ? null : people.get(party);
        List<String> wordsOf = new ArrayList<>(nameForms(party));
        if (me != null) for (String a : me.also()) if (FamilyForms.script(a).equals("latin")) for (String w : a.split("[\\s,()]+")) if (w.length() >= 3 && !TITLE_WORD.matcher(w).matches()) wordsOf.add(w);
        for (String w : wordsOf) {
            if (w.equals(party) || KanjiForms.modern(w).equals(KanjiForms.modern(party))) { ways.add(w); continue; }
            boolean familyPart = me != null && !me.family().isBlank() && FamilyForms.sameForm(me.family(), w);
            boolean shared = people != null && people.values().stream().anyMatch(o -> !o.name().equals(party) && nameForms(o.name()).stream().anyMatch(x -> !x.equals(o.name()) && flat(x).equals(flat(w))));
            if (familyCounts || !familyPart && !shared) ways.add(w);
        }
        if (familyCounts && me != null && !me.family().isBlank()) ways.add(me.family());
        for (String w : ways) { String f = flat(w); if (f.length() >= 2 && !skip.contains(f) && fq.contains(f)) return true; }
        // a name of one character (勇) is written where no other character touches it: 勇の娘, not 勇太
        if (party.codePointCount(0, party.length()) == 1 && Character.UnicodeScript.of(party.codePointAt(0)) == Character.UnicodeScript.HAN
                && Pattern.compile("(?<![\\p{IsHan}\\p{IsKatakana}])" + Pattern.quote(party) + "(?![\\p{IsHan}\\p{IsKatakana}])").matcher(q).find()) return true;
        List<String> chain = relationWords(party);
        if (!chain.isEmpty()) return says(q, chain);
        if ((party.equals(teller) || isWriter(party, teller)) && FIRST_PERSON.matcher(q).find()) return true;
        Matcher pr = THIRD_PERSON.matcher(q);
        if (!pr.find()) return false;
        String before = flat(q.substring(0, pr.start()));
        if (people != null) for (Person o : people.values()) if (!o.name().equals(party)) for (String w : waysOf(o.name(), people)) if (flat(w).length() >= 2 && before.contains(flat(w))) return false;
        String rawBefore = q.substring(0, pr.start());
        return !TWO_CAPITALS.matcher(rawBefore).find() && !Pattern.compile("[\\p{IsHan}\\p{IsKatakana}]{2,}").matcher(rawBefore).find();
    }

    /** The words that begin the sentence of a fact filed about the relative the words name, not the one the model gave ({@link #anchoredByWords}). */
    public static final String ANCHORED = "was filed about the relative the words name, as";

    /**
     * A described party anchored on the account's own I where the words speak of somebody else's relative: "kimie's father is a parent of Ann"
     * from "Ann's name is Wilma … a name given to her by her banker father" names Ann's father, not the writer's. When the description starts from
     * the writer (or the owner), the words say no I, the relation word is owned by his, her or their, and the other party is the first person the
     * words name, the description is anchored on the other party instead, and the read says so.
     */
    static Fact anchoredByWords(Fact f, String teller, Map<String, Person> people, List<String> dropped) {
        if (!(f.relation().equals("parent-of") || f.relation().equals("child-of"))) return f;
        String q = Normalizer.normalize(f.quote() == null ? "" : f.quote(), Normalizer.Form.NFKC);
        if (q.isBlank() || FIRST_PERSON.matcher(q).find()) return f;
        for (boolean subject : new boolean[]{true, false}) {
            String party = subject ? f.subject() : f.object(), other = subject ? f.object() : f.subject();
            String[] d = FamilyLinks.description(party);
            if (d == null || relationWords(other).size() > 0) continue;
            String anchor = d[0];
            if (!(anchor.equalsIgnoreCase(teller) || isWriter(anchor, teller) || anchor.equalsIgnoreCase(FamilyClose.OWNER))) continue;
            List<String> steps = FamilyLinks.steps(d[1]);
            String last = steps.get(steps.size() - 1).replaceAll("^(?:late|maternal|paternal)\\s+", "");
            Matcher m = Pattern.compile("(?i)(?<![\\p{L}])(his|her|their)\\s+(?:[\\p{L}-]+\\s+){0,2}?" + Pattern.quote(last) + "(?![\\p{L}])").matcher(q);
            if (!m.find()) continue;
            String before = q.substring(0, m.start());
            int otherAt = -1;
            for (String w : waysOf(other, people)) { int at = flat(w).length() >= 2 ? flat(before).indexOf(flat(w)) : -1; if (at >= 0 && (otherAt < 0 || at < otherAt)) otherAt = at; }
            if (otherAt < 0) continue;
            // the other party is the first person the words name: no other name before it
            String head = flat(before).substring(0, otherAt);
            boolean someoneFirst = false;
            if (people != null) for (Person o : people.values()) if (!o.name().equals(other)) for (String w : waysOf(o.name(), people)) if (flat(w).length() >= 2 && head.contains(flat(w))) someoneFirst = true;
            if (someoneFirst) continue;
            String to = other + "'s " + String.join("'s ", steps);
            Fact anchored = subject ? new Fact(to, f.relation(), f.object(), f.date(), f.quote(), f.detail()) : new Fact(f.subject(), f.relation(), to, f.date(), f.quote(), f.detail());
            dropped.add("\"" + sentence(f.subject(), f.relation(), f.object()) + "\" " + ANCHORED + " \"" + sentence(anchored.subject(), anchored.relation(), anchored.object()) + "\", because the words say "
                    + m.group(1).toLowerCase(Locale.ROOT) + " " + last + ", " + other + "'s, and no I of " + anchor + "'s.");
            return anchored;
        }
        return f;
    }

    /**
     * Whether a name carries the person's given name in any script: the person's given part, or a word of the reading, against the name's
     * given part or its words, as one form ({@link FamilyForms#sameForm}); 健二 with the reading けんじ is Kenji.
     */
    static boolean sharesGivenAcrossScripts(Person p, String name, String given) {
        List<String> mine = new ArrayList<>();
        if (!p.given().isBlank()) mine.add(p.given());
        if (!p.reading().isBlank()) for (String w : p.reading().split("[\\s　・]+")) if (!w.isBlank()) mine.add(w);
        for (String a : p.also()) if (FamilyForms.script(a).equals("kana")) for (String w : a.split("[\\s　・]+")) if (!w.isBlank()) mine.add(w);
        if (mine.isEmpty()) return false;
        List<String> theirs = new ArrayList<>();
        if (given != null && !given.isBlank()) theirs.add(given);
        for (String w : name.split("[\\s,()（）]+")) if (w.length() >= 2 && !TITLE_WORD.matcher(w).matches()) theirs.add(w);
        for (String m : mine) for (String t : theirs) if (FamilyForms.sameForm(m, t, true)) return true;
        return false;
    }

    // titles and initials in a name in Latin letters, which are no given name
    private static final Pattern TITLE_WORD = Pattern.compile("(?i)^(?:mr|mrs|ms|miss|dr|rev|revd|fr|father|mother|sir|lady|lord|prof|professor|capt|col|gen|hon|jr|sr|the)\\.?$");

    /**
     * Whether two names in Latin letters share a given name: a word of two letters or more that is neither a title nor the family part of the
     * first, or an initial that begins one of the first's given words ("H. Kano" for Amos Kano). Relatives share a family name, not a given one.
     */
    static boolean sharesGiven(Person p, String other) {
        String family = p.family().isBlank() ? "" : unaccented(p.family()).toLowerCase(Locale.ROOT);
        List<String> mine = new ArrayList<>();
        for (String w : unaccented(p.name()).split("[\\s,()]+")) { String x = w.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}]", ""); if (x.length() >= 2 && !TITLE_WORD.matcher(w).matches() && !x.equals(family)) mine.add(x); }
        if (!p.given().isBlank()) for (String w : unaccented(p.given()).toLowerCase(Locale.ROOT).split("\\s+")) if (!w.isBlank()) mine.add(w.replaceAll("[^\\p{L}]", ""));
        for (String w : unaccented(other).split("[\\s,()]+")) {
            String x = w.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}]", "");
            if (x.isEmpty() || TITLE_WORD.matcher(w).matches()) continue;
            if (x.length() == 1 && w.endsWith(".")) { for (String m : mine) if (m.startsWith(x)) return true; continue; }
            if (mine.contains(x)) return true;
        }
        return false;
    }

    /**
     * Another spelling the model gave a person is left out when it is somebody else of the read: another person of the read, with facts of their
     * own, whom the text does not write as one with this person; or a name in Latin letters, beside a name in Latin letters, that shares no given
     * name with it and that the text never writes in brackets beside it ("Tai Lindqvist", the book's editor, is no spelling of its writer). Each is
     * said. A spelling in another script is left as it is: the reading rules weigh it.
     */
    static void alsoOfOthers(Map<String, Person> people, List<Fact> facts, String text, List<String> dropped) {
        Set<String> words = latinWords(people);
        for (Person p : new ArrayList<>(people.values())) {
            if (p.also().isEmpty()) continue;
            List<String> keep = new ArrayList<>();
            for (String a : p.also()) {
                String other = personWritten(a, people, p.name());
                boolean bracketedTogether = !bracketed(text, p.name(), a, words).isEmpty() || !bracketed(text, a, p.name(), words).isEmpty();
                // in the person's own script only: a spelling in another script is a reading, which the reading rules pair with the person
                if (other != null && FamilyMentions.full(other) && FamilyForms.script(other).equals(FamilyForms.script(p.name())) && !FamilyForms.sameForm(other, p.name()) && !sharesGiven(p, other) && !bracketedTogether && facts.stream().anyMatch(f -> f.subject().equals(other) || f.object().equals(other)))
                    dropped.add("\"" + a + "\" was not kept as another spelling of " + p.name() + ", because " + a + " is another person in this text, with facts of their own.");
                else keep.add(a);
            }
            if (keep.size() != p.also().size()) people.put(p.name(), new Person(p.name(), p.reading(), keep, p.family(), p.given()));
        }
    }

    /** The second rule of {@link #alsoOfOthers}, applied as each piece is read: a spelling in Latin letters that shares no given name with the person's and stands nowhere in brackets beside it. */
    static void alsoNotWrittenAsOne(Map<String, Person> people, String text, List<String> dropped) {
        Set<String> words = latinWords(people);
        for (Person p : new ArrayList<>(people.values())) {
            if (p.also().isEmpty() || !FamilyForms.script(p.name()).equals("latin")) continue;
            List<String> keep = new ArrayList<>();
            for (String a : p.also()) {
                boolean bracketedTogether = !bracketed(text, p.name(), a, words).isEmpty() || !bracketed(text, a, p.name(), words).isEmpty();
                if (FamilyForms.script(a).equals("latin") && !sharesGiven(p, a) && !bracketedTogether) dropped.add("\"" + a + "\" was not kept as another spelling of " + p.name() + ", because the text never writes " + a + " and " + p.name() + " as one person, and the two share no given name.");
                else keep.add(a);
            }
            if (keep.size() != p.also().size()) people.put(p.name(), new Person(p.name(), p.reading(), keep, p.family(), p.given()));
        }
    }

    private static Set<String> latinWords(Map<String, Person> people) {
        Set<String> words = new HashSet<>();
        for (Person p : people.values()) if (FamilyForms.script(p.name()).equals("latin")) for (String x : p.name().split("[\\s,]+")) if (!x.isBlank()) words.add(plain(x).toLowerCase(Locale.ROOT));
        return words;
    }

    /** The words that begin the sentence of a fact filed with the relation its own words say instead of the model's ({@link #relationByWords}). */
    public static final String AS_THE_WORDS_SAY = "was filed with the relation its words say, as";

    /**
     * A parent, a child or a brother or sister as the reading gives it, checked against the words' own kind. A parent-of or child-of whose
     * words say no plain parent or child word, and say a grandparent, is a relation of another kind: a grandparent is no parent, and with no
     * grandparent relation in the vocabulary the fact is filed as relative-of, its words kept for the link pass to walk. Words that say only a
     * step-parent, a parent-in-law, a godparent, an adoptive or a foster parent file that relation. A sibling-of whose every brother or sister
     * word is owned by a relation word ("her father's younger brother") names a brother of the father, not of the fact's other party: it stays
     * sibling-of only when that party is a described person of that relation ("…'s mother's father"), else it is filed as relative-of. Words
     * that say a plain parent, child, brother or sister ("Ann Hale's father Tom Hale") change nothing, and so do words that say nothing.
     */
    static Fact relationByWords(Fact f, String teller, Map<String, Person> people, Set<String> known, List<String> dropped) { return relationByWords(f, teller, people, Set.of(), known, dropped); }

    // a relation word owned by one word: "Endo's son", 遠藤の長男
    private static final Pattern ONE_WORD_OWNER = Pattern.compile("(?i)(?<![\\p{L}])(\\p{Lu}\\p{L}+)['’]s\\s+" + "(?:(?:only|eldest|elder|oldest|older|younger|youngest|first|second|third|late)\\s+)*(?:son|daughter|child|children|father|mother|parents?)(?![\\p{L}])"
            + "|(?<![\\p{IsHan}])([\\p{IsHan}]{1,3})の(?:長男|次男|二男|三男|長女|次女|二女|三女|息子|娘|父|母|子)");

    /**
     * Whether the words own a parent or child word by a family name alone that the read knows ("Endo's son", 遠藤の長男): who that is, is the
     * mention rules' to weigh ({@link FamilyMentions}), and the words rules leave the fact to them.
     */
    private static boolean familyNameOwns(String quote, Set<String> familyParts, Map<String, Person> people) {
        Matcher m = ONE_WORD_OWNER.matcher(Normalizer.normalize(quote == null ? "" : quote, Normalizer.Form.NFKC));
        while (m.find()) {
            String w = m.group(1) != null ? m.group(1) : m.group(2).replaceFirst("(?<=.)家$", "");   // 遠藤家の次男: the house is the family's word
            if (FamilyMentions.known(w, familyParts)) return true;
            for (Person p : people.values()) if (!p.family().isBlank() && FamilyForms.sameForm(p.family(), w, true)) return true;
        }
        return false;
    }

    static Fact relationByWords(Fact f, String teller, Map<String, Person> people, Set<String> familyParts, Set<String> known, List<String> dropped) {
        String rel = f.relation();
        String to = null, why = null;
        // a described party whose own words the quote does not carry: the read leaves the fact out as it is ({@link #relativesSaid})
        for (String party : List.of(f.subject(), f.object())) { List<String> chain = relationWords(party); if (!chain.isEmpty() && !says(Normalizer.normalize(f.quote(), Normalizer.Form.NFKC), chain)) return f; }
        boolean parent = rel.equals("parent-of") || rel.equals("child-of");
        String parentSide = rel.equals("parent-of") ? f.subject() : f.object(), childSide = rel.equals("parent-of") ? f.object() : f.subject();
        if (parent) {
            FamilyKin.Kinds k = FamilyKin.kinds(f.quote(), waysOf(f.subject(), people), waysOf(f.object(), people));
            switch (k.instead()) {
                case "grand" -> { to = "relative-of"; why = "say a grandparent, which is no parent"; }
                case "step" -> { to = "step-parent-of"; why = "say a step-parent, not a parent by birth"; }
                case "in-law" -> { to = "parent-in-law-of"; why = "say a parent-in-law, not a parent by birth"; }
                case "god" -> { to = "godparent-of"; why = "say a godparent, not a parent by birth"; }
                case "adoptive" -> { to = "adopted-by"; why = "say an adoptive parent, not a parent by birth"; }
                case "foster" -> { to = "foster-child-of"; why = "say a foster parent, not a parent by birth"; }
                case "several" -> { to = "relative-of"; why = "say no parent by birth, and more than one kind of relative"; }
                case "as-if" -> { dropped.add(leftOutWords(f, "say a likeness, such as \"like a father\", which is no parent")); return null; }
                case "chain" -> { dropped.add(leftOutWords(f, "say a parent or a child only as a step to somebody else, such as \"my mother's friend\"")); return null; }
                case "others" -> { if (familyNameOwns(f.quote(), familyParts, people)) return f; dropped.add(leftOutWords(f, "speak of somebody else's parent or child, not of " + parentSide + " and " + childSide)); return null; }
                default -> { }
            }
            // a parent claim in prose names both: a party the words neither write nor stand for by a pronoun or the account's own I is the model's
            // guess; a party the words own by a family name alone ("Endo's son") is the mention rules' to weigh
            if (to == null && !tableLabel(f.quote()) && !familyNameOwns(f.quote(), familyParts, people)) for (String party : List.of(f.subject(), f.object()))
                if (!spokenOf(f.quote(), party, teller, people, Set.of(), true)) { dropped.add(leftOutWords(f, "do not write " + party + ", and no word in them stands for " + party)); return null; }
        } else if (rel.equals("sibling-of")) {
            if (FamilyKin.spousesOnly(f.quote(), waysOf(f.subject(), people), waysOf(f.object(), people))) { dropped.add(leftOutWords(f, "speak of a wife or a husband, not of a brother or sister")); return null; }
            if (FamilyKin.siblingWords(f.quote()) < 0) {
                List<String> a = relationWords(f.subject()), b = relationWords(f.object());
                boolean described = !a.isEmpty() || !b.isEmpty();
                if (!described) { to = "relative-of"; why = "say a brother or sister of a relative, not of " + f.subject() + " or " + f.object() + " by name"; }
            }
        }
        if (to == null || !known.contains(to)) return f;
        Fact filed = switch (to) {
            case "step-parent-of", "parent-in-law-of", "godparent-of" -> new Fact(parentSide, to, childSide, f.date(), f.quote(), f.detail());
            case "adopted-by", "foster-child-of" -> new Fact(childSide, to, parentSide, f.date(), f.quote(), f.detail());
            default -> new Fact(f.subject(), to, f.object(), f.date(), f.quote(), parent ? Map.of() : f.detail());
        };
        dropped.add("\"" + sentence(f.subject(), f.relation(), f.object()) + "\" " + AS_THE_WORDS_SAY + " \"" + sentence(filed.subject(), filed.relation(), filed.object())
                + "\", because the words given for it (\"" + Acquisitions.compress(f.quote(), 60) + "\") " + why + ".");
        return filed;
    }

    /** The ways a read writes a person: the name, the other spellings and the reading the read gives, and the given name. */
    private static List<String> waysOf(String name, Map<String, Person> people) {
        List<String> out = new ArrayList<>(List.of(name));
        Person p = people == null ? null : people.get(name);
        if (p != null) { out.addAll(p.also()); if (!p.reading().isBlank()) out.add(p.reading()); if (!p.given().isBlank()) out.add(p.given()); }
        return out;
    }

    /**
     * A web page's read with the page itself in no fact: a person the reading wrote as the page's title and host ("森田健二 - Wikipedia
     * (ja.wikipedia.org)") is the person the title names (森田健二), when the page's text writes that name; otherwise the fact is left out, and
     * said. The page is the source of what it says, and the note the owner wrote beside its address is filed apart, as the owner's own account.
     */
    static Read pageNoParty(Read read, String page, String text) {
        String subject = FamilyLinks.pageSubject(page);
        String title = page.replaceAll("\\s*\\((?:[\\w-]+\\.)+[a-z]{2,}\\)\\s*$", "").strip();
        String to = subject != null && !subject.equals(title) && personLike(subject) && standsWhole(subject, inTextKey(text)) ? subject : null;
        Function<String, Boolean> isPage = n -> {
            if (n == null || n.isBlank()) return false;
            String x = n.strip();
            return x.startsWith(page) || x.contains("the person who keeps this library notes") || flat(x).equals(flat(title)) || flat(x).equals(flat(page));
        };
        boolean any = read.people().stream().anyMatch(p -> isPage.apply(p.name())) || read.facts().stream().anyMatch(f -> isPage.apply(f.subject()) || (personToPerson(f.relation()) || associate(f.relation())) && isPage.apply(f.object()))
                || read.names().stream().anyMatch(n -> isPage.apply(n.person()));
        if (!any) return read;
        List<String> dropped = new ArrayList<>(read.dropped());
        List<Fact> facts = new ArrayList<>();
        for (Fact f : read.facts()) {
            boolean s = isPage.apply(f.subject()), o = (personToPerson(f.relation()) || associate(f.relation())) && isPage.apply(f.object());
            if (!s && !o) { facts.add(f); continue; }
            if (to == null) { dropped.add("\"" + sentence(f.subject(), f.relation(), f.object()) + "\" was left out, because it makes the page itself a person, and the page's title names nobody the page writes about."); continue; }
            facts.add(new Fact(s ? to : f.subject(), f.relation(), o ? to : f.object(), f.date(), f.quote(), f.detail()));
        }
        List<Person> people = new ArrayList<>();
        for (Person p : read.people()) {
            if (!isPage.apply(p.name())) { people.add(p); continue; }
            if (to != null && people.stream().noneMatch(x -> x.name().equals(to))) people.add(new Person(to, p.reading(), p.also().stream().filter(a -> !isPage.apply(a)).toList(), p.family(), p.given()));
        }
        List<NameRead> names = new ArrayList<>();
        for (NameRead n : read.names()) if (!isPage.apply(n.person())) names.add(n); else if (to != null) names.add(new NameRead(to, n.name(), n.family(), n.given(), n.forms(), n.kind(), n.said(), n.date(), n.quote()));
        return new Read(people, facts, dropped, read.twoWays(), names, read.families());
    }

    // the words of a title that is about a family, a place or a record rather than one person
    private static final Pattern NOT_A_PERSON = Pattern.compile("(?i)(?<![\\p{L}])(?:family|families|history|genealogy|clan|house|archives?|records?|register|index|home|homepage|page|site|blog|society|museum|list)(?![\\p{L}])|家系|系図|系譜|一族|家$|一覧|記録|資料");

    /** Whether a page's title part can be one person's name: a full name of five words at most, which no word of a family, a place or a record is part of. */
    static boolean personLike(String name) {
        String n = name == null ? "" : name.strip();
        return FamilyMentions.full(n) && n.split("\\s+").length <= 5 && !NOT_A_PERSON.matcher(n).find() && !n.matches(".*\\d.*");
    }

    /** Who a text is by, and who translated it, as its first pages say it: the names as the text writes them, and the line that says so. */
    public record Byline(String writer, String translator, String quote) { }

    /** How much of a text's beginning says whom it is by: its title page, the page after it and the start of a preface. */
    static final int FIRST_PAGES = 8_000;

    /**
     * Whom a book is by, asked once of its first pages: the model gives the writer's and the translator's names as the text writes them and
     * quotes the line that says so, and a name is kept only when that line is in the text and carries the name. Null when the pages say
     * nothing the library can keep.
     */
    public static Byline byline(String text, Model model) {
        if (text == null || text.isBlank() || model == null) return null;
        String first = text.substring(0, Math.min(text.length(), FIRST_PAGES));
        String raw = model.answer(bylinePrompt(first));
        int a = raw == null ? -1 : raw.indexOf('{'), b = raw == null ? -1 : raw.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        JsonNode v;
        try { v = J.readTree(raw.substring(a, b + 1)); } catch (Exception notJson) { return null; }
        String quote = v.path("quote").asText("").strip(), whole = flat(first);
        if (!said(whole, quote) && stitched(whole, quote) == null) return null;
        String writer = inQuote(v.path("writer").asText(""), quote), translator = inQuote(v.path("translator").asText(""), quote);
        if (!writer.isEmpty() && isWriter(writer, translator)) writer = "";
        if (writer.isEmpty() && translator.isEmpty()) return null;
        return new Byline(writer, translator, quote);
    }

    /** A name the words carry, every word of it, in either order; "" for none, a stand-in or a name the words do not carry. */
    private static String inQuote(String name, String quote) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || FamilyQuestions.placeholder(n) || n.length() > 80) return "";
        String q = flat(quote);
        if (q.contains(flat(n))) return n;
        for (String w : n.split("[\\s,]+")) if (!w.isBlank() && !q.contains(flat(w))) return "";
        return n;
    }

    static String bylinePrompt(String first) {
        return "Below are the first pages of a book or a long document. Say whom it is by, as its own words say it: a title page, a line such as \"by …\", "
                + "\"the memoir of …\" or \"translated by …\", a catalogue entry, or a preface its writer signed. The writer is the person whose own text it is; "
                + "a translator or an editor goes in `translator` or stays out.\n\n"
                + "Answer with JSON only, in this shape: {\"writer\": \"\", \"translator\": \"\", \"quote\": \"\"}. `writer` is the writer's name as the text writes it, "
                + "`translator` the translator's, and `quote` the line of the text that names them, copied exactly. Give \"\" for a name the text does not give.\n\n"
                + "THE FIRST PAGES:\n" + first;
    }

    // a heading that marks a part as the translator's own: "Translator's Note", 訳者あとがき
    private static final Pattern TRANSLATORS_PART = Pattern.compile("(?i)^\\s*(?:(?:a|the)\\s+)?translator['’]?s?['’]?\\s+(?:note|notes|preface|introduction|foreword|afterword|remarks|postscript)\\s*$|^\\s*(?:訳者(?:あとがき|まえがき|序文?|解説|付記)|訳注)\\s*$");
    // a heading that marks a part as an editor's, or as the editor's and the translator's: "Editor's Note", "with Editor's and Translator's Remarks", 編者あとがき
    private static final Pattern EDITORS_PART = Pattern.compile("(?i)^.{0,40}\\beditor['’]?s?['’]?\\b.{0,40}\\b(?:note|notes|preface|introduction|foreword|afterword|remarks|postscript)\\s*$"
            + "|^\\s*(?:foreword|introduction|preface)\\s+by\\s+\\S.{0,50}$|^\\s*(?:編者|編集者)(?:あとがき|まえがき|序文?|解説|付記)\\s*$");
    // a heading that begins another part: a chapter, a preface, the text itself
    private static final Pattern OTHER_PART = Pattern.compile("(?i)^\\s*(?:chapter|part|preface|foreword|introduction|prologue|epilogue|acknowledg\\w*|contents|the\\s+memoirs?|第[一二三四五六七八九十百\\d]+[章部])\\b.{0,60}$");

    /** The sentence of the text the words stand in, from the end of the sentence before them to the end of their own; "" when they are not in the text. */
    static String sentenceOf(String text, String quote) {
        String t = text == null ? "" : text, q = quote == null ? "" : quote.strip();
        int at = q.isEmpty() ? -1 : t.indexOf(q.length() > 30 ? q.substring(0, 30) : q);
        if (at < 0) return "";
        int from = at;
        while (from > 0 && at - from < 600 && ".!?。！？".indexOf(t.charAt(from - 1)) < 0 && !t.startsWith("\n\n", from - 2)) from--;
        int to = Math.min(t.length(), at + q.length());
        while (to < t.length() && to - at < 1200 && ".!?。！？".indexOf(t.charAt(to - 1)) < 0) to++;
        return t.substring(from, to);
    }

    /**
     * Whose part of a text the words stand in, by the last heading before them: "translator" under a translator's own heading, "editor" under an
     * editor's heading (or the editor's and the translator's together), "" under any other heading or none, where the text speaks as its writer.
     */
    static String partOf(String text, String quote) {
        String t = text == null ? "" : text, q = quote == null ? "" : quote.strip();
        int at = q.isEmpty() ? -1 : t.indexOf(q.length() > 30 ? q.substring(0, 30) : q);
        if (at < 0) return "";
        String whose = "";
        for (String line : t.substring(0, at).split("\\R")) {
            if (line.isBlank() || line.strip().length() > 90) continue;
            if (TRANSLATORS_PART.matcher(line).find()) whose = "translator";
            else if (EDITORS_PART.matcher(line).find()) whose = "editor";
            else if (OTHER_PART.matcher(line).find()) whose = "";
        }
        return whose;
    }

    static boolean isWriter(String name, String writer) {
        if (name == null || writer == null || name.isBlank()) return false;
        if (flat(name).equals(flat(writer))) return true;
        Set<String> keys = FamilyNames.keys(name);
        keys.retainAll(FamilyNames.keys(writer));
        return !keys.isEmpty();
    }

    static String voiceQuestion(Voice voice, String quote, String passage) {
        String who = voice.writer() + (voice.writtenAs().isBlank() ? "" : " (its first pages write the name " + voice.writtenAs() + ")");
        String by = voice.memoir() ? ", the memoir " + who + " wrote of their own life" : ", a text written by " + who;
        return "Below is a passage from " + voice.where() + by + (voice.translator().isBlank() ? "" : ", translated by " + voice.translator()) + ".\n\nPASSAGE:\n" + passage + "\n\n"
                + "The words \"" + quote + "\" say I, me, my, we or our. Are these words " + voice.writer() + "'s own, written in " + voice.writer() + "'s own voice as the writer of the text? "
                + (voice.memoir() ? "In the memoir's own text the one who says I is " + voice.writer() + ". Words are somebody else's when the passage quotes somebody else, such as a letter, an interview or a speech, "
                        + "or stands in a part by somebody else, such as an editor's introduction or a translator's note."
                : "Words are somebody else's when the passage quotes them: an interview, a letter, a diary, a memoir, a speech, or another person telling their own story"
                        + (voice.translator().isBlank() ? "" : ", and so are the translator's or an editor's own remarks") + ".");
    }

    /** About {@code span} characters of the text on each side of the quote; the start of the text when the quote cannot be found in it. */
    static String around(String text, String quote, int span) {
        String t = text == null ? "" : text, q = quote == null ? "" : quote.strip();
        int at = q.isEmpty() ? -1 : t.indexOf(q);
        if (at < 0 && q.length() > 12) at = t.indexOf(q.substring(0, 12));
        if (at < 0) return t.substring(0, Math.min(t.length(), span * 2));
        return t.substring(Math.max(0, at - span), Math.min(t.length(), at + q.length() + span));
    }

    /**
     * A link note that changed: the claims the old note beside this address gave, whose words the new note no longer carries, are superseded by
     * the new note, with a note that says so. How many were superseded. The old claims stay on record; a reset keeps them as it keeps every
     * told claim.
     */
    public static int noteChanged(LibraryStore store, String locator, String told) throws IOException {
        String now = flat(told);
        int gone = 0;
        for (Finding f : store.scanFindings().findings()) {
            if (f.state() == Finding.State.superseded || f.state() == Finding.State.retired) continue;
            if (f.sources().stream().noneMatch(s -> locator.equals(s.locator()))) continue;
            String was = flat(FamilyChecks.quoteOf(f).replaceFirst("(?i)^About [^:]{1,80}: ", ""));
            if (was.isEmpty() || now.contains(was) || was.contains(now)) continue;
            List<Finding.Note> notes = new ArrayList<>(f.notes());
            notes.add(new Finding.Note("supersedes", "family-account", LocalDate.now().toString(), "the note beside this link changed: it now says \"" + told + "\""));
            store.write(new Finding(f.id(), f.title(), f.subjects(), Finding.State.superseded, f.claimType(), f.confidence(), f.writer(), f.recordedAt(), f.validAsOf(), f.volatility(), f.reviewBy(), f.sources(), f.supersedes(), f.review(), f.body(), f.triple(), notes));
            gone++;
        }
        return gone;
    }

    /** File what was read: people as nodes (their readings and older spellings as other names), one draft claim per relation. */
    public static Outcome file(LibraryStore store, Read given, String locator, String teller) throws IOException {
        return file(store, given, locator, teller, "");
    }

    /**
     * {@code against}: what the quotes were checked against when that is not the source's own text, such as a model's reading of a
     * picture ({@link FamilyTranscript#against}). Each claim keeps it as a note; "" for none.
     */
    public static Outcome file(LibraryStore store, Read given, String locator, String teller, String against) throws IOException {
        return fileAsRead(store, possible(store, given, teller), locator, teller, against);
    }

    /**
     * A read whose facts each cite their own sources, such as the pages of a register read as one text: {@code cites} gives a fact's
     * locators, {@code notes} the notes its claim carries.
     */
    public static Outcome file(LibraryStore store, Read given, String teller, Function<Fact, List<String>> cites, Function<Fact, List<Finding.Note>> notes) throws IOException {
        return fileAsRead(store, possible(store, given, teller), teller, cites, notes);
    }

    /** Files a read as it stands, without asking whether it can be true: what a tree file or an older library may hold, which the checks are for. */
    static Outcome fileAsRead(LibraryStore store, Read given, String locator, String teller) throws IOException { return fileAsRead(store, given, locator, teller, ""); }

    static Outcome fileAsRead(LibraryStore store, Read given, String locator, String teller, String against) throws IOException {
        List<Finding.Note> checked = against == null || against.isBlank() ? List.of() : List.of(new Finding.Note(FamilyTranscript.NOTE, "family-account", LocalDate.now().toString(), against));
        return fileAsRead(store, given, teller, f -> List.of(locator), f -> checked);
    }

    static Outcome fileAsRead(LibraryStore store, Read given, String teller, Function<Fact, List<String>> cites, Function<Fact, List<Finding.Note>> notes) throws IOException {
        FamilyPeople.holdsAFamily(store);   // the library holds family work from now on: the family's pages are in the menu
        Graph heldGraph = FamilyPeople.view(store);
        // the questions about these people that waited already: the read's own are the ones it adds
        Set<String> askedBefore = codes(FamilyNameQuestions.about(store, heldGraph, touched(heldGraph, given)));
        // the families the read speaks of, found or made before anything is filed about them: a fact names a family by its label
        Map<String, String> families = new LinkedHashMap<>();
        Read placed = oneMembershipEach(withFamilies(store, heldGraph, namesFromFacts(given), families));
        Map<Fact, String> workedOut = new LinkedHashMap<>();
        Read worked = yearsWorkedOut(placed, workedOut);
        // a fact the owner disputed or retired is filed again only as a note on that claim, and one the library disputed by itself only as a
        // further source of it: while it is disputed or retired it places nobody in time and gives nobody a sex
        Set<Fact> refused = new HashSet<>();
        List<Finding> heldShelf = store.scanFindings().findings();
        for (Fact f : worked.facts()) { Finding held = told(sameFact(heldShelf, f)); if (held != null && (held.state() == Finding.State.disputed || held.state() == Finding.State.retired)) refused.add(f); }
        Read read = withSexes(worked, refused);
        Set<String> names = new LinkedHashSet<>();
        Map<String, Person> byName = new LinkedHashMap<>();
        for (Person p : read.people()) byName.put(p.name(), p);
        // only the people a kept fact is about: a biography names colleagues, teachers and officials, and they are not the family. A family's
        // own fact (its seat, its founding, the family it branched from) is about the family, which is no person
        // a family is never filed as a person, whatever side of a fact it stands on: the families of this read by their labels, and any the
        // library holds or the words name
        Set<String> familyLabels = new LinkedHashSet<>(families.values());
        Set<String> personParts = FamilyMentions.personFamilyParts(heldGraph, read);
        List<String> readFamilies = FamilyMentions.familiesWritten(read);
        for (Fact f : read.facts()) {
            if (!FamilyHouses.OF_A_FAMILY.contains(f.relation())) names.add(f.subject());
            if (personToPerson(f.relation()) || associate(f.relation()) || f.relation().equals(FamilyHouses.FOUNDED)) names.add(f.object());
        }
        for (NameRead n : read.names()) if (!FamilyNameHistory.kind(n.kind()).equals("hereditary")) names.add(n.person());
        names.removeIf(n -> familyLabels.contains(n) || FamilyMentions.familyParty(heldGraph, n, readFamilies, personParts));
        // every person of this read, whatever the facts kept: an other name that is one of them is a second person, not a second name
        Set<String> readNames = new LinkedHashSet<>();
        for (String n : names) readNames.add(Vocabulary.norm(n));
        for (Person p : read.people()) readNames.add(Vocabulary.norm(p.name()));
        List<String> keptApart = new ArrayList<>();
        for (String name : names) {
            Person p = byName.get(name);
            Graph.setKind(store, name, "person");
            List<String> other = new ArrayList<>();
            if (p != null) { if (!p.reading().isEmpty()) other.add(p.reading()); for (String a : p.also()) if (!other.contains(FamilyNames.asOtherName(a))) other.add(FamilyNames.asOtherName(a)); }
            // the names the read gives the person, and their written forms: within one read they are one person's (a record that links them).
            // An index form is kept without its comma, as the list of names would part it there
            for (NameRead n : read.names()) if (n.person().equals(name)) for (String o : nameAndForms(n)) if (!other.contains(FamilyNames.asOtherName(o)) && FamilyNames.keepAsOtherName(name, o)) other.add(FamilyNames.asOtherName(o));
            String modern = KanjiForms.modern(name);
            if (!modern.equals(name)) other.add(modern);
            other.removeIf(o -> o.equalsIgnoreCase(name));
            // an other name that already belongs to somebody else would join the two, and every claim about the one would be about the
            // other: a daughter named for her mother, whose married name is the mother's maiden name. It is left out, and said
            String self = heldGraph.nodeIdOf(name);
            for (Iterator<String> it = other.iterator(); it.hasNext(); ) {
                String o = it.next(), there = heldGraph.nodeIdOf(o);
                boolean elsewhere = !there.equals(self) && (heldGraph.node(there) != null || heldGraph.curated().get(there) != null);
                if (!elsewhere && !(readNames.contains(Vocabulary.norm(o)) && !Vocabulary.norm(o).equals(Vocabulary.norm(name)))) continue;
                it.remove();
                keptApart.add("\"" + o + "\" was not kept as another name of " + name + ", because " + o + " is another person in your library. If they are one person after all, researchzosho graph merge \"" + o + "\" \"" + name + "\" joins them.");
            }
            // the other names keep where they came from: the source of the first fact that names the person, else of the first name the read gives them
            String source = read.facts().stream().filter(f -> f.subject().equals(name) || f.object().equals(name)).flatMap(f -> cites.apply(f).stream()).findFirst()
                    .orElse(read.names().stream().filter(n -> n.person().equals(name)).flatMap(n -> cites.apply(asFact(n)).stream()).findFirst().orElse(""));
            // a party written as one word, or as a title with one word (Endo, Mr. Endo), is nobody the library can find by that word: every
            // source writes somebody else so. Other names written to it would lead the next source's Endo to this person
            if (FamilyNames.oneWord(name)) other.clear();
            if (!other.isEmpty()) Graph.alias(store, name, other, source);
        }
        int claims = 0, held = 0, added = 0;
        Map<String, String> firstClaim = new LinkedHashMap<>();   // person → the first claim filed about them
        Map<Fact, String> claimOf = new LinkedHashMap<>();   // each fact → the claim that says it, filed now or held before
        List<String> dropped = new ArrayList<>(read.dropped());
        dropped.addAll(keptApart);
        List<Finding> shelf = new ArrayList<>(store.scanFindings().findings());
        for (Fact f : read.facts()) {
            List<Finding.Source> mine = sources(cites.apply(f), teller);
            if (f.relation().equals("life-event")) Graph.setKind(store, f.object(), "event");
            else if (f.relation().equals("sex")) Graph.setKind(store, f.object(), "value");
            else if (f.relation().equals(FamilyHouses.MEMBER) || f.relation().equals(FamilyHouses.BRANCH) || f.relation().equals(FamilyHouses.FOUNDED)) { }   // a family, made above; a founder, a person
            else if (!personToPerson(f.relation()) && !associate(f.relation()) && !f.relation().equals("occupation") && !f.relation().equals("aged") && !f.relation().equals("sex") && !f.relation().endsWith("-on")) Graph.setKind(store, f.object(), "place");
            Finding.Triple t = new Finding.Triple(f.subject(), f.relation(), f.object());
            List<Finding.Note> withDetail = new ArrayList<>(notes.apply(f));
            if (!f.detail().isEmpty()) withDetail.add(FamilyDetail.note(f.detail(), "family-account"));
            List<Finding> same = sameFact(shelf, f);
            // a membership read two ways that are not one (born into the family, and its head from 1920) is two claims, and neither is the other told again
            if (f.relation().equals(FamilyHouses.MEMBER)) same = same.stream().filter(x -> oneReading(new Fact(f.subject(), f.relation(), f.object(), f.date(), f.quote(), FamilyDetail.of(x)), f)).toList();
            if (same.isEmpty()) same = splitOff(shelf, f, mine);
            if (!same.isEmpty()) {
                held++;
                // the fact again from another source: that source is kept with the claim, never thrown away
                Finding kept = told(same);
                if (kept == null) continue;
                claimOf.put(f, kept.id());
                Finding now = Evidence.toldAgain(store, kept, mine, "family-account", f.quote(), toldWith(kept, f, notes.apply(f)));
                if (now == null) continue;
                shelf.set(shelf.indexOf(kept), now);
                // a fact the library disputed by itself keeps the text as a further source, so later sources can settle it
                if (now.state() == Finding.State.draft || now.state() == Finding.State.accepted || now.setAsideBy() == Finding.SetAside.library) added++;
                else dropped.add("\"" + sentence(f.subject(), f.relation(), f.object()) + "\" was not filed again, because it is a fact that is " + (now.state() == Finding.State.disputed ? "disputed" : "retired")
                        + " (" + now.id().replaceFirst("^(F-\\d+).*", "$1") + "). A note on it now says that this text says it again.");
                continue;
            }
            FamilyDate d = FamilyDate.parse(f.relation().endsWith("-on") ? f.object() : f.date());
            if (f.relation().endsWith("-on") && d == null) continue;   // a birth "on" something that names no year says nothing
            String when = workedOut.containsKey(f) && !f.relation().endsWith("-on") ? " (" + f.date() + ")" : f.relation().endsWith("-on") ? (f.object().contains(String.valueOf(d.year())) ? "" : " (" + d.phrase() + ")") : d != null ? " (" + d.shown() + ")" : f.date().isBlank() ? "" : " (" + f.date() + ")";
            String claim = sentence(f.subject(), f.relation(), f.object(), f.detail()) + when + ".";
            String id = store.nextFindingId(f.subject() + " " + f.relation() + " " + f.object());
            firstClaim.putIfAbsent(f.subject(), id);
            claimOf.put(f, id);
            Finding finding = new Finding(id, Acquisitions.compress(claim, 80), List.of(), Finding.State.draft, Finding.ClaimType.extraction,
                    Finding.Confidence.low, "family-account", Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "",
                    mine, List.of(), null, claim + "\n\nThe account says: \"" + f.quote() + "\"\n" + (workedOut.containsKey(f) ? "\n" + workedOut.get(f) + "\n" : ""), t, withDetail);
            store.write(finding);
            shelf.add(finding);
            claims++;
        }
        // the names the read gives, one claim each, with the claim of the event that caused the name when the read has it
        int nameClaims = 0;
        for (NameRead n : read.names()) {
            NameRead on = n;
            if (FamilyNameHistory.kind(n.kind()).equals("hereditary")) {
                // a hereditary head name is the family's: filed on the family, found or made as the read's other families are
                String label = families.get(familyKey(n.person(), ""));
                if (label == null) label = FamilyHouses.family(store, heldGraph, n.person(), "", "", List.of(n.person()));
                if (label == null) continue;
                on = new NameRead(label, n.name(), n.family(), n.given(), n.forms(), n.kind(), n.said(), n.date(), n.quote());
            }
            Fact as = asFact(on);
            Map<String, String> more = new LinkedHashMap<>();
            String event = eventOf(on, read.facts(), claimOf);
            if (!event.isEmpty()) more.put("event", event);
            int before = shelf.size();
            Finding f = FamilyNameHistory.file(store, on, sources(cites.apply(as), teller), "family-account", notes.apply(as), more, shelf);
            if (f == null) continue;
            nameClaims++;
            if (shelf.size() > before) { claims++; firstClaim.putIfAbsent(on.person(), f.id()); } else { held++; added++; }
        }
        // a name the text reads two ways: a note on the first claim about the person, and a sentence for the person who reads it
        List<String> twoWays = new ArrayList<>();
        for (String[] t : read.twoWays()) {
            twoWays.add("The text reads " + t[0] + "'s name two ways: " + t[1] + " and " + t[2] + ". Both are kept as other names of " + t[0] + ". The reading decides what a search looks for, so a record that gives the reading, such as a register with the reading beside the name, tells which is right.");
            String id = firstClaim.get(t[0]);
            Finding f = id == null ? null : store.finding(id);
            if (f != null) store.write(f.withNote(new Finding.Note("read-two-ways", "family-account", LocalDate.now().toString(), "read two ways in " + String.join(", ", f.sources().stream().map(Finding.Source::locator).toList()) + ": " + t[1] + " / " + t[2])));
        }
        // the people the read wrote only by a family name: each is linked to a person when the library's rule allows, and said
        List<String> mentions = new ArrayList<>();
        for (String n : names) if (FamilyMentions.isMention(n) && !mentions.contains(n)) mentions.add(n);
        List<String> linked = mentions.isEmpty() ? List.of() : FamilyMentions.afterFiling(store, mentions);
        store.circulate("family-account", names.size() + " people, " + claims + " draft claims from " + teller + "'s account" + (added > 0 ? ", " + added + " held claims backed by it too" : ""));
        // whom nothing places in the past once the read is in: the graph works it out from every claim, as it does each time it is read
        Graph now = FamilyPeople.view(store);
        int mayBeLiving = 0;
        for (String name : names) { Graph.Node n = now.node(now.nodeIdOf(name)); if (n != null && n.mayBeLiving()) mayBeLiving++; }
        List<String> raised = new ArrayList<>();
        for (String c : codes(FamilyNameQuestions.about(store, now, touched(now, read)))) if (!askedBefore.contains(c)) raised.add(c);
        return new Outcome(names.size(), claims, held, mayBeLiving, dropped, added, twoWays, nameClaims, new LinkedHashSet<>(families.values()).size(), mentions.size(), linked, raised);
    }

    /** The sources of a claim a family's account gives, each as told by the teller. */
    private static List<Finding.Source> sources(List<String> locators, String teller) {
        return locators.stream().map(l -> new Finding.Source(l, "as told by " + teller, "the family's own account: a place to start, not yet checked against a record")).toList();
    }

    /** A name as the fact it is: {@code person | has-name | name: <name>}, with its date and quote, for what cites a fact. */
    static Fact asFact(NameRead n) { return new Fact(n.person(), FamilyNameHistory.PREDICATE, FamilyNameHistory.VALUE + n.name(), n.date(), n.quote()); }

    /** A name and its other written forms, as the read gives them. */
    private static List<String> nameAndForms(NameRead n) {
        List<String> out = new ArrayList<>();
        // the name as the list of names keeps it: "Endo, Kenji" as Endo Kenji, since the list parts other names at their commas
        if (!n.name().isBlank()) out.add(FamilyNames.asOtherName(n.name()));
        for (String f : n.forms()) if (f != null && !f.isBlank() && !out.contains(f.strip())) out.add(f.replaceAll("\\s*[,，、]\\s*", " ").strip());
        return out;
    }

    /** A has-name fact a read gives among its facts is one of its names: filed as a name, with its reading, never as a fact of its own. */
    static Read namesFromFacts(Read read) {
        if (read.facts().stream().noneMatch(f -> f.relation().equals(FamilyNameHistory.PREDICATE))) return read;
        List<Fact> facts = new ArrayList<>();
        List<NameRead> names = new ArrayList<>(read.names());
        for (Fact f : read.facts()) {
            if (!f.relation().equals(FamilyNameHistory.PREDICATE)) { facts.add(f); continue; }
            Map<String, String> d = f.detail();
            List<String> forms = new ArrayList<>();
            for (FamilyNameHistory.Form x : FamilyNameHistory.formsOf(d.getOrDefault("forms", ""))) forms.add(x.text());
            names.add(new NameRead(f.subject(), FamilyNameHistory.bare(f.object()), d.getOrDefault("family", ""), d.getOrDefault("given", ""), forms, d.getOrDefault("kind", ""), d.getOrDefault("said", ""),
                    f.date().isBlank() ? d.getOrDefault("from", "") : f.date(), f.quote()));
        }
        return new Read(read.people(), facts, read.dropped(), read.twoWays(), names, read.families());
    }

    /** Which sides of a fact name a family: {subject, object}. */
    static boolean[] familySides(String relation) {
        return switch (relation) {
            case FamilyHouses.MEMBER -> new boolean[]{false, true};
            case FamilyHouses.BRANCH -> new boolean[]{true, true};
            case FamilyHouses.SEAT, FamilyHouses.FOUNDED -> new boolean[]{true, false};
            default -> new boolean[]{false, false};
        };
    }

    /** How a read's family is looked up among the families it named: its family name in one form, and its seat. */
    private static String familyKey(String written, String seat) {
        String n = FamilyHouses.familyName(written);
        String k = FamilyForms.script(n).equals("han") ? FamilyForms.hanKey(n) : FamilyForms.script(n).equals("kana") ? FamilyForms.kanaKey(n) : FamilyForms.latinKey(n);
        return k + "|" + Vocabulary.norm(seat == null ? "" : seat);
    }

    /**
     * The read with each family it speaks of found or made ({@link FamilyHouses#family}) and written by its label: the families it lists,
     * with their seats as facts of their own, and every family a membership, a branch, a seat or a founding names. {@code made}: the key of
     * each family as the read wrote it → its label.
     */
    static Read withFamilies(LibraryStore store, Graph g, Read read, Map<String, String> made) throws IOException {
        boolean any = !read.families().isEmpty();
        Set<String> personParts = FamilyMentions.personFamilyParts(g, read);
        List<String> readFamilies = FamilyMentions.familiesWritten(read);
        Set<Fact> ofAFamily = new HashSet<>();   // a fact of a life whose subject is a family: where the 森田家 lived is the family's fact
        for (Fact f : read.facts()) {
            boolean[] sides = familySides(f.relation());
            any |= sides[0] || sides[1];
            if (!sides[0] && !sides[1] && !personToPerson(f.relation()) && !associate(f.relation()) && !f.relation().equals(FamilyNameHistory.PREDICATE)
                    && FamilyMentions.familyParty(g, f.subject(), readFamilies, personParts)) ofAFamily.add(f);
        }
        any |= !ofAFamily.isEmpty();
        if (!any) return read;
        List<Fact> facts = new ArrayList<>();
        List<Fact> seats = new ArrayList<>();
        for (FamilyRead fr : read.families()) {
            String name = fr.name().isBlank() ? fr.written() : fr.name();
            if (FamilyHouses.familyName(name).isBlank()) continue;
            String first = "";
            for (Fact f : read.facts()) if (f.relation().equals(FamilyHouses.MEMBER) && (f.object().equals(fr.written()) || f.object().equals(fr.name()))) { first = f.subject(); break; }
            String label = made.get(familyKey(name, fr.seat()));
            if (label == null) label = FamilyHouses.family(store, g, name, fr.seat(), first, List.of(fr.written()));
            if (label == null) continue;
            made.put(familyKey(name, fr.seat()), label);
            made.putIfAbsent(familyKey(name, ""), label);
            if (!fr.written().isBlank()) made.putIfAbsent(familyKey(fr.written(), ""), label);
            if (!fr.seat().isBlank() && !fr.quote().isBlank()) seats.add(new Fact(label, FamilyHouses.SEAT, fr.seat(), "", fr.quote()));
        }
        for (Fact f : read.facts()) {
            boolean[] sides = familySides(f.relation());
            if (ofAFamily.contains(f)) sides = new boolean[]{true, false};
            if (!sides[0] && !sides[1]) { facts.add(f); continue; }
            String subject = f.subject(), object = f.object();
            if (sides[0]) subject = placed(store, g, subject, "", made);
            if (sides[1]) object = placed(store, g, object, f.relation().equals(FamilyHouses.MEMBER) ? f.subject() : "", made);
            facts.add(subject == null || object == null ? f : new Fact(subject, f.relation(), object, f.date(), f.quote(), f.detail()));
        }
        for (Fact s : seats) if (facts.stream().noneMatch(f -> f.subject().equals(s.subject()) && f.relation().equals(s.relation()) && f.object().equals(s.object()))) facts.add(s);
        return new Read(read.people(), facts, read.dropped(), read.twoWays(), read.names(), read.families());
    }

    /**
     * One membership for one person in one family from one sentence: a read that gives an adoption into the Takahashi family and the heirship of
     * it from the same words gives one entry, as its heir by adoption. The readings are joined; the first says what they share.
     */
    static Read oneMembershipEach(Read read) {
        List<Fact> out = new ArrayList<>();
        for (Fact f : read.facts()) {
            int at = -1;
            if (f.relation().equals(FamilyHouses.MEMBER))
                for (int i = 0; i < out.size(); i++) { Fact o = out.get(i); if (o.relation().equals(FamilyHouses.MEMBER) && o.subject().equals(f.subject()) && o.object().equals(f.object()) && flat(o.quote()).equals(flat(f.quote())) && oneReading(o, f)) { at = i; break; } }
            if (at < 0) { out.add(f); continue; }
            Fact o = out.get(at);
            Map<String, String> d = new LinkedHashMap<>(o.detail());
            f.detail().forEach((k, v) -> { if (!d.containsKey(k) || "unstated".equals(d.get(k)) && !"unstated".equals(v)) d.put(k, v); });
            out.set(at, new Fact(o.subject(), o.relation(), o.object(), o.date().isBlank() ? f.date() : o.date(), o.quote(), d));
        }
        return out.size() == read.facts().size() ? read : new Read(read.people(), out, read.dropped(), read.twoWays(), read.names(), read.families());
    }

    /**
     * What a fact told again brings to the claim that holds it: its notes, and what its words say of how (how somebody came into a family or
     * left it, their role, an adoption's kind) where the claim does not say it yet, as the fact's own claim would have carried it.
     */
    private static List<Finding.Note> toldWith(Finding held, Fact f, List<Finding.Note> notes) {
        Map<String, String> had = FamilyDetail.of(held), now = new LinkedHashMap<>(had);
        for (String k : List.of("how", "left", "role", "kind", "took", "ended", "to")) {
            String v = f.detail().getOrDefault(k, "");
            if (!Set.of("", "unstated").contains(v) && Set.of("", "unstated").contains(now.getOrDefault(k, ""))) now.put(k, v);
        }
        if (now.equals(had)) return notes;
        List<Finding.Note> out = new ArrayList<>(notes);
        out.add(FamilyDetail.note(now, "family-account"));
        return out;
    }

    /**
     * Whether two readings of a membership can be one: no key they both give differs (a way not stated differs from none), and their dates agree.
     * A role (head, heir) one gives and the other does not joins only a reading that says nothing the one with the role does not say too (how
     * they came in, how they left, until when, the year), as the family's page joins them: born into the family and its head from 1920 are
     * two things, and so two claims, while "adopted as heir into the Takahashi family in 1940" given once as the adoption and once as its heir
     * from 1940 is one.
     */
    private static boolean oneReading(Fact a, Fact b) {
        for (Map.Entry<String, String> e : a.detail().entrySet()) {
            String other = b.detail().get(e.getKey());
            if (other != null && !other.equals(e.getValue()) && !"unstated".equals(other) && !"unstated".equals(e.getValue())) return false;
        }
        String ra = a.detail().getOrDefault("role", ""), rb = b.detail().getOrDefault("role", "");
        if (!ra.equals(rb)) {
            Fact plain = ra.isEmpty() ? a : b, roled = plain == a ? b : a;
            for (String k : List.of("how", "left", "to")) { String v = plain.detail().getOrDefault(k, ""); if (!Set.of("", "unstated").contains(v) && !v.equals(roled.detail().get(k))) return false; }
            if (!plain.date().isBlank() && !plain.date().equals(roled.date())) return false;
        }
        return a.date().isBlank() || b.date().isBlank() || a.date().equals(b.date());
    }

    /** The label of the family a read wrote this way, found or made; the words as written when they name no family. */
    private static String placed(LibraryStore store, Graph g, String written, String firstMember, Map<String, String> made) throws IOException {
        String label = made.get(familyKey(written, ""));
        if (label != null) return label;
        String id = g.nodeIdOf(written);
        if (FamilyHouses.isFamily(g, id)) { label = FamilyHouses.labelOf(g, id); made.put(familyKey(written, ""), label); return label; }
        label = FamilyHouses.family(store, g, written, "", firstMember, List.of(written));
        if (label == null) return written;
        made.put(familyKey(written, ""), label);
        return label;
    }

    /**
     * The claim of the event that caused a name, as the read has it: a fact about the same person with the relation the kind implies (婿養子
     * and an adoption: adopted-by; a marriage and 入夫: married-to; succession: a membership as head or heir, or heir-of; a name taken back:
     * the adoption or the marriage that ended), from the same words, else of the same year. "" when the read has none.
     */
    static String eventOf(NameRead n, List<Fact> facts, Map<Fact, String> claimOf) {
        Set<String> implied = switch (FamilyNameHistory.kind(n.kind())) {
            case "mukoyoshi", "adoptive" -> Set.of("adopted-by");
            case "marriage", "nyufu" -> Set.of("married-to");
            case "succession" -> Set.of(FamilyHouses.MEMBER, "heir-of");
            case "taken-back" -> Set.of("adopted-by", "married-to");
            default -> Set.of();
        };
        if (implied.isEmpty()) return "";
        List<Fact> fit = new ArrayList<>();
        for (Fact f : facts) {
            if (!implied.contains(f.relation()) || !claimOf.containsKey(f)) continue;
            boolean mine = f.subject().equals(n.person()) || (f.relation().equals("married-to") && f.object().equals(n.person()));
            if (!mine) continue;
            if (f.relation().equals(FamilyHouses.MEMBER) && !Set.of("head", "heir").contains(f.detail().getOrDefault("role", ""))) continue;
            if (FamilyNameHistory.kind(n.kind()).equals("taken-back") && f.detail().getOrDefault("ended", "").isBlank() && f.detail().getOrDefault("to", "").isBlank()) continue;
            fit.add(f);
        }
        for (Fact f : fit) if (!flat(f.quote()).isEmpty() && flat(f.quote()).equals(flat(n.quote()))) return claimOf.get(f);
        FamilyDate nd = FamilyDate.parse(n.date());
        if (nd != null) for (Fact f : fit) { FamilyDate fd = FamilyDate.parse(f.date()); if (fd != null && fd.year() == nd.year()) return claimOf.get(f); }
        return "";
    }

    /** The node ids a read is about in a graph: its people, both sides of its facts, and the people its names belong to. */
    public static Set<String> touched(Graph g, Read read) {
        Set<String> out = new LinkedHashSet<>();
        for (Person p : read.people()) out.add(g.nodeIdOf(p.name()));
        for (Fact f : read.facts()) { out.add(g.nodeIdOf(f.subject())); if (personToPerson(f.relation()) || associate(f.relation())) out.add(g.nodeIdOf(f.object())); }
        for (NameRead n : read.names()) out.add(g.nodeIdOf(n.person()));
        out.remove("");
        // a party the read wrote only by a family name is filed as somebody described beside a person it touched: "森田健二's parent (written only as Endo)"
        for (Graph.Node n : g.nodes()) { String[] p = FamilyMentions.parts(n.label()); if (p != null && out.contains(g.nodeIdOf(p[0]))) out.add(n.id()); }
        return out;
    }

    private static Set<String> codes(List<FamilyNameQuestions.Question> qs) {
        Set<String> out = new LinkedHashSet<>();
        for (FamilyNameQuestions.Question q : qs) out.add(q.code());
        return out;
    }

    /**
     * The held claims that say this fact. The same place with a year the held claim does not allow is another claim: the two years are
     * kept, and the check shows them. The same date at another precision ("1886", "1886年8月1日") is the same fact: a less exact one is
     * not filed beside a more exact one.
     */
    static List<Finding> sameFact(List<Finding> shelf, Fact f) {
        Finding.Triple t = new Finding.Triple(f.subject(), f.relation(), f.object());
        List<Finding> same = shelf.stream().filter(x -> x.triple() != null && x.triple().sameKey(t) && Finding.Triple.canon(x.triple().object()).equals(Finding.Triple.canon(t.object()))
                && (t.predicate().endsWith("-on") || datesFit(x, f.date()))).toList();
        if (same.isEmpty() && t.predicate().endsWith("-on")) {
            FamilyDate date = FamilyDate.parse(t.object());
            if (date != null) same = shelf.stream().filter(x -> x.triple() != null && x.triple().sameKey(t) && sameDateCoarser(date, FamilyDate.parse(x.triple().object()))).toList();
        }
        return same;
    }

    /**
     * The held claims that say this fact, from this source, of the person a split moved it to ({@link FamilySplit}): the same words read
     * again stay with the person the owner gave them to, and are not filed again on the first person of the name.
     */
    static List<Finding> splitOff(List<Finding> shelf, Fact f, List<Finding.Source> from) {
        List<Finding> out = new ArrayList<>();
        for (Finding x : shelf) {
            String[] m = x.triple() == null ? null : FamilySplit.moved(x);
            if (m == null || x.sources().stream().noneMatch(s -> from.stream().anyMatch(h -> Evidence.sameLocator(h.locator(), s.locator())))) continue;
            String first = Vocabulary.norm(m[1]);
            Fact there = new Fact(Vocabulary.norm(f.subject()).equals(first) ? m[0] : f.subject(), f.relation(), Vocabulary.norm(f.object()).equals(first) ? m[0] : f.object(), f.date(), f.quote());
            if (!there.equals(f) && !sameFact(List.of(x), there).isEmpty()) out.add(x);
        }
        return out;
    }

    /**
     * Whether a further source that gives a fact with this date may join a held claim of the same fact. The date of a birth, a death,
     * a marriage or a home is written in brackets after the claim: when both give a year and no year of the one fits the other, or
     * when only the new source gives one, they are two claims, so the check can show the two years and no year is lost.
     */
    static boolean datesFit(Finding held, String date) {
        FamilyDate now = FamilyDate.parse(date == null ? "" : date);
        if (now == null) return true;
        String first = held.body().lines().findFirst().orElse("");
        if (held.triple() != null) for (String name : new String[]{held.triple().subject(), held.triple().object()}) if (name != null && !name.isBlank()) first = first.replace(name, " ");
        FamilyDate was = FamilyDate.parse(lastBracket(first));
        return was != null && !FamilyDate.apart(was, now, 0);
    }

    /** What the last brackets of a line hold, brackets inside them included ("明治十九年 (1886)"); "" when the line does not end with brackets. */
    static String lastBracket(String line) {
        String l = line.strip().replaceFirst("[.。]$", "").strip();
        if (!l.endsWith(")") && !l.endsWith("）")) return "";
        int depth = 0;
        for (int i = l.length() - 1; i >= 0; i--) {
            char c = l.charAt(i);
            if (c == ')' || c == '）') depth++;
            else if ((c == '(' || c == '（') && --depth == 0) return l.substring(i + 1, l.length() - 1);
        }
        return "";
    }

    /**
     * Of the claims that say one fact, the one a new source joins: a draft or an accepted claim first, then one the person disputed or
     * retired (their decision holds, and the claim is only told the fact came back), then one the library disputed by itself; null when
     * only replaced ones say it.
     */
    static Finding told(List<Finding> same) {
        for (Finding x : same) if (x.state() == Finding.State.draft || x.state() == Finding.State.accepted) return x;
        for (Finding x : same) if (x.setAsideBy() == Finding.SetAside.person) return x;
        for (Finding x : same) if (x.setAsideBy() == Finding.SetAside.library) return x;
        return null;
    }


    /**
     * Two readings of a name that say the same: the same kana in hiragana or katakana, with or without spaces, or the same romanised
     * words in any order. A kana reading and a romanised one are not compared: they are the same reading in two scripts.
     */
    static boolean sameReading(String a, String b) {
        String x = readingKey(a), y = readingKey(b);
        if (x.isEmpty() || y.isEmpty()) return true;
        boolean kx = x.codePoints().allMatch(c -> c >= 0x3040 && c <= 0x309f || c == 0x30fc), ky = y.codePoints().allMatch(c -> c >= 0x3040 && c <= 0x309f || c == 0x30fc);
        boolean lx = x.matches("[a-z ]+"), ly = y.matches("[a-z ]+");
        if (kx && ky) return x.equals(y);
        if (lx && ly) { String[] wx = x.split(" "), wy = y.split(" "); Arrays.sort(wx); Arrays.sort(wy); return Arrays.equals(wx, wy); }
        return true;
    }

    /** A reading in one form: katakana as hiragana, no spaces or dots in kana, Latin letters without accents in lower case. */
    static String readingKey(String reading) {
        String n = Normalizer.normalize(reading == null ? "" : reading, Normalizer.Form.NFKC).strip();
        StringBuilder b = new StringBuilder();
        n.codePoints().forEach(c -> b.appendCodePoint(c >= 0x30a1 && c <= 0x30f6 ? c - 0x60 : c));
        String k = b.toString();
        if (k.codePoints().anyMatch(c -> c >= 0x3040 && c <= 0x309f)) return k.replaceAll("[\\s・=＝-]+", "");
        return Normalizer.normalize(k, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z ]+", " ").replaceAll("\\s+", " ").strip();
    }


    /**
     * A register writes a date against the one before it: 同日, 同月十日, 同年, 翌年. Such a date gets its year from the last date written before
     * it in the same quote, else from the last dated fact read from that quote, and says so: the year is kept in brackets beside the
     * words as written, and {@code workedOut} gets the sentence the claim carries about where the year came from. A date with no year
     * and nothing to work one out from is left as it was.
     */
    static Read yearsWorkedOut(Read read, Map<Fact, String> workedOut) {
        List<Fact> out = new ArrayList<>();
        Map<String, FamilyDate> lastInQuote = new LinkedHashMap<>();
        for (Fact f : read.facts()) {
            boolean on = f.relation().endsWith("-on");
            String text = on ? f.object() : f.date();
            String quote = f.quote() == null ? "" : f.quote();
            FamilyDate d = FamilyDate.parse(text);
            if (d == null && FamilyDate.relative(text)) {
                int at = quote.indexOf(text.strip());
                if (at < 0) at = quote.indexOf(text.strip().substring(0, Math.min(2, text.strip().length())));   // the words may differ in their numerals; 同年, 翌年 lead
                FamilyDate anchor = at > 0 ? FamilyDate.lastIn(quote.substring(0, at)) : null;
                if (anchor == null) anchor = lastInQuote.get(quote);
                d = FamilyDate.parse(text, anchor);
                if (d != null) {
                    String dated = text.strip() + " (" + d.phrase() + ", the year worked out from the entry's earlier date)";
                    Fact worked = on ? new Fact(f.subject(), f.relation(), dated, f.date(), f.quote(), f.detail()) : new Fact(f.subject(), f.relation(), f.object(), dated, f.quote(), f.detail());
                    workedOut.put(worked, "The account writes the date as \"" + text.strip() + "\"; the year " + d.phrase() + " was worked out from \"" + anchor.written() + "\", the date written before it.");
                    f = worked;
                }
            }
            if (d != null) lastInQuote.put(quote, d);
            out.add(f);
        }
        return read.with(read.people(), out, read.dropped(), read.twoWays());
    }

    /**
     * The sex the model gave each person, kept only where the text's own words say it: a word for a woman or a man (she, her, wife, daughter,
     * 妻, 娘; he, his, husband, son, 夫, 長男) stands in a sentence the model quoted for one of that person's own facts, the facts whose subject
     * is that person, or for one of their names, and is about that person; in a parent's sentence a son or a daughter is the child's word, in
     * a marriage's a husband or a wife the other one's ({@link FamilyKin#ownWords}), and the "she" of "She married Tom Ellis" is not Tom's.
     * A sentence with words for both says nothing ({@link FamilyKin#sexInWords(String, Collection, Collection, Collection)}). Never from a
     * name, nor from a character in one (the 夫 that ends a given name). Words for the other sex in another of
     * those sentences, a father or a mother, a son or a daughter the quotes of a parent claim make of them ({@link #withSexes}), or the model
     * giving both, leave it out. Each sex kept is a fact of its own, from the sentence that says it, as written.
     */
    static List<Fact> sexesInWords(List<Person> people, List<Fact> facts, List<NameRead> names, Map<String, Set<String>> given) {
        List<Fact> out = new ArrayList<>();
        // the ways the read writes each of its people, and the family parts of their names: a name's letters are no word about anybody, and a
        // word about another person the sentence names is not this person's
        Map<String, Set<String>> writes = new LinkedHashMap<>();
        Set<String> parts = new LinkedHashSet<>();
        for (Person p : people) {
            Set<String> w = writes.computeIfAbsent(p.name(), k -> new LinkedHashSet<>());
            w.add(p.name());
            w.addAll(p.also());
            for (String x : List.of(p.reading(), p.given())) if (!x.isBlank()) w.add(x);
            if (!p.family().isBlank()) parts.add(p.family());
        }
        for (Fact f : facts) if (personToPerson(f.relation())) for (String side : List.of(f.subject(), f.object()))
            if (!FamilyQuestions.placeholder(side) && writes.values().stream().noneMatch(w -> w.contains(side))) writes.computeIfAbsent(side, k -> new LinkedHashSet<>()).add(side);
        for (Person p : people) {
            Set<String> said = given.getOrDefault(p.name(), Set.of());
            if (said.size() != 1) continue;
            String sex = said.iterator().next();
            Set<String> own = new HashSet<>(p.also());
            own.add(p.name());
            if (facts.stream().anyMatch(f -> f.relation().equals("sex") && own.contains(f.subject()))) continue;
            Set<String> mine = writes.getOrDefault(p.name(), Set.of()), theirs = new LinkedHashSet<>();
            writes.forEach((who, w) -> { if (!who.equals(p.name())) for (String x : w) if (!mine.contains(x)) theirs.add(x); });
            Map<String, String> quotes = new LinkedHashMap<>();   // each sentence as written → the words in it that may be the person's own
            for (Fact f : facts) if (own.contains(f.subject())) quotes.merge(f.quote(), FamilyKin.ownWords(f.relation(), f.quote()), (a, b) -> a.length() <= b.length() ? a : b);
            for (NameRead n : names) if (own.contains(n.person())) quotes.putIfAbsent(n.quote(), n.quote());
            String found = null;
            boolean against = false, parentWord = false;
            for (Map.Entry<String, String> q : quotes.entrySet()) {
                // a name the sentence gives as the person's name ("she was Mary Hart", signed "M. Ellis") names the name, not the one who acts
                Set<String> told = new LinkedHashSet<>(parts);
                for (NameRead n : names) if (own.contains(n.person()) && q.getKey().equals(n.quote())) { told.add(n.name()); told.addAll(n.forms()); }
                Set<String> named = new LinkedHashSet<>(mine);
                named.removeAll(told);
                String s = FamilyKin.sexInWords(q.getValue(), named, theirs, told);
                if (s.equals(sex)) { if (found == null) found = q.getKey(); }
                else if (!s.isEmpty()) against = true;
            }
            for (Fact f : facts) {
                String[] w = FamilyKin.sexFromQuote(f.relation(), f.subject(), f.object(), f.quote());
                if (w == null || !own.contains(w[0])) continue;
                if (w[1].equals(sex)) parentWord = true; else against = true;
            }
            if (found != null && !against && !parentWord) out.add(new Fact(p.name(), "sex", sex, "", found));
        }
        return out;
    }

    // one step of a relative written only by relation words: "'s mother", "'s eldest son", "'s great-grandmother", "'s maternal grandfather", "'s parents"
    private static final Pattern RELATION_STEP = Pattern.compile("(?i)['’]s\\s+(?:(?:elder|eldest|older|oldest|younger|youngest|first|second|third|only|late|maternal|paternal)\\s+)*"
            + "((?:great[- ]?)*(?:grand)?(?:mother|father|parent|wife|husband|spouse|son|daughter|child|brother|sister|uncle|aunt|cousin|nephew|niece))(?:s|ren)?\\s*$");

    /** The relation words of a person written only by them, in order ("the writer of notes.txt's mother's father": mother, father); empty for anybody else. */
    static List<String> relationWords(String label) {
        List<String> chain = new ArrayList<>();
        String rest = label == null ? "" : label.strip();
        for (Matcher m = RELATION_STEP.matcher(rest); m.find(); m = RELATION_STEP.matcher(rest)) {
            chain.add(0, m.group(1).toLowerCase(Locale.ROOT).replace(' ', '-'));
            rest = rest.substring(0, m.start()).strip();
        }
        return rest.isEmpty() ? List.of() : chain;
    }

    // the everyday words for a relation word, as defaults behind the rule that the account's own words for a relative stand in a quote about them
    private static final Map<String, List<String>> EVERYDAY = Map.of(
            "mother", List.of("mum", "mom", "mam", "mama", "mamma", "mummy", "mommy"), "father", List.of("dad", "daddy", "papa"),
            "grandmother", List.of("gran", "granny", "grannie", "grandma", "grandmama", "nan", "nana", "nanna"),
            "grandfather", List.of("grandpa", "grandpapa", "grampa", "grandad", "granddad", "gramps"));

    // the Japanese words for each relation word, each kept apart from the longer words it is a part of: 曾祖母 is no 祖母, 大叔母 no 叔母,
    // 孫娘 no 娘, 兄嫁 no 兄, 親戚 no 親, and the 子 that ends a name is no child
    private static final Map<String, String> RELATION_WORDS = Map.ofEntries(
            Map.entry("father", "(?<![祖曾曽高叔伯義養継大])父(?![母方])|おとうさん|おとうちゃん|とうちゃん"),
            Map.entry("mother", "(?<![祖曾曽高叔伯義養継大父])母(?![方校国屋語])|おかあさん|おかあちゃん|かあちゃん"),
            Map.entry("parent", "両親|父母|(?<![両父母])親(?![戚友類族方])"), Map.entry("wife", "妻|(?<![兄弟])嫁|家内|女房"),
            Map.entry("husband", "(?<![丈工])夫(?![人婦])|主人|旦那"), Map.entry("spouse", "配偶者|伴侶"),
            Map.entry("son", "息子|長男|次男|二男|三男|四男|五男|末男|倅"), Map.entry("daughter", "(?<!孫)娘(?!婿)|長女|次女|二女|三女|四女|五女|末女"),
            Map.entry("child", "子供|子ども|こども|実子"), Map.entry("brother", "(?<![義従])[兄弟](?![子嫁])|おにいさん"), Map.entry("sister", "(?<![義従])[姉妹](?!婿)|おねえさん"),
            Map.entry("sibling", "兄弟|姉妹|きょうだい"), Map.entry("uncle", "(?<!大)(?:叔父|伯父)|おじさん|おじちゃん"), Map.entry("aunt", "(?<!大)(?:叔母|伯母)|おばさん|おばちゃん"),
            Map.entry("cousin", "従兄|従弟|従姉|従妹|いとこ"), Map.entry("nephew", "(?<!又)甥"), Map.entry("niece", "(?<!又)姪"),
            Map.entry("grandfather", "(?<![曾曽高])祖父(?!母)|(?<!ひい)おじい(?:さん|ちゃん)|(?<![おい])じい(?:さん|ちゃん)"),
            Map.entry("grandmother", "(?<![曾曽高])祖母|(?<!ひい)おばあ(?:さん|ちゃん)|(?<![おい])ばあ(?:さん|ちゃん)"), Map.entry("grandparent", "(?<![曾曽高])祖父母"),
            Map.entry("grandchild", "(?<![曾曽玄ひ])孫(?![娘息])"), Map.entry("grandson", "(?<![曾曽玄ひ])孫息子"), Map.entry("granddaughter", "(?<![曾曽玄ひ])孫娘"),
            Map.entry("great-grandfather", "[曾曽]祖父(?!母)|ひいおじい(?:さん|ちゃん)"), Map.entry("great-grandmother", "[曾曽]祖母|ひいおばあ(?:さん|ちゃん)"),
            Map.entry("great-grandparent", "[曾曽]祖父母"), Map.entry("great-grandchild", "[曾曽]孫|ひ孫|ひまご"), Map.entry("great-great-grandfather", "高祖父(?!母)"),
            Map.entry("great-great-grandmother", "高祖母"), Map.entry("great-great-grandparent", "高祖父母"), Map.entry("great-great-grandchild", "玄孫|やしゃご"),
            Map.entry("great-uncle", "大叔父|大伯父"), Map.entry("great-aunt", "大叔母|大伯母"));

    // a word for both of a pair counts for each of them, and each of them for the pair: parents are a father and a mother, a wife is a spouse
    private static final Map<String, List<String>> PAIRS = Map.of(
            "parent", List.of("father", "mother"), "grandparent", List.of("grandfather", "grandmother"), "great-grandparent", List.of("great-grandfather", "great-grandmother"),
            "great-great-grandparent", List.of("great-great-grandfather", "great-great-grandmother"), "child", List.of("son", "daughter"),
            "grandchild", List.of("grandson", "granddaughter"), "great-grandchild", List.of("great-grandson", "great-granddaughter"),
            "great-great-grandchild", List.of("great-great-grandson", "great-great-granddaughter"), "sibling", List.of("brother", "sister"), "spouse", List.of("wife", "husband"));

    /** The words that say one relation word: the word, and the word for its pair or the two words of the pair it names. */
    private static List<String> alike(String word) {
        List<String> out = new ArrayList<>(List.of(word));
        out.addAll(PAIRS.getOrDefault(word, List.of()));
        PAIRS.forEach((pair, two) -> { if (two.contains(word)) out.add(pair); });
        return out;
    }

    /** An English relation word as a word of its own: not a part of a longer one (a great-grandmother, a step-mother, a mother-in-law), in the singular or the plural. */
    private static Pattern english(String word) {
        String plural = word.endsWith("child") ? "(?:ren)?" : "s?";
        return Pattern.compile("(?i)(?<![\\p{L}])(?<!great[- ])(?<!step[- ])(?<!god[- ])(?<!half[- ])(?<!foster[- ])" + Pattern.quote(word).replace("-", "\\E[- ]?\\Q") + plural
                + "(?![\\p{L}])(?![- ]in[- ]law)");
    }

    /** Whether a quote carries one relation word, standing alone: the word or one of the words it stands in for, in English, in the everyday words, or in Japanese. */
    static boolean saysRelationWord(String quote, String word) {
        return saysRelationWord(quote, word, true);
    }

    private static boolean saysRelationWord(String quote, String word, boolean pairs) {
        for (String w : pairs ? alike(word.toLowerCase(Locale.ROOT)) : List.of(word.toLowerCase(Locale.ROOT))) {
            if (english(w).matcher(quote).find()) return true;
            for (String e : EVERYDAY.getOrDefault(w, List.of())) if (english(e).matcher(quote).find()) return true;
            String other = RELATION_WORDS.get(w);
            if (other != null && Pattern.compile(other).matcher(quote).find()) return true;
        }
        return false;
    }

    // a chain of relation words a quote spells out: "my father's mother", "Mum's dad", 父の母
    private static final String STEP_WORD = "(?:great[- ]?)*(?:grand)?(?:mother|father|parent|son|daughter|child|brother|sister)|mum|mom|dad";
    private static final Pattern CHAIN = Pattern.compile("(?i)(?<![\\p{L}])(?<!great[- ])(?<!step[- ])(?<!god[- ])(?<!half[- ])(?<!foster[- ])(?:" + STEP_WORD + ")(?:['’]s\\s+(?:" + STEP_WORD + "))+(?![\\p{L}])(?![- ]in[- ]law)"
            + "|(?<![祖曾曽高叔伯義養継大])(?:父|母|兄|弟|姉|妹|息子|娘)(?:の(?:父|母|兄|弟|姉|妹|息子|娘))+");
    private static final Map<String, String> STEP_OF = Map.ofEntries(Map.entry("mum", "mother"), Map.entry("mom", "mother"), Map.entry("dad", "father"), Map.entry("父", "father"),
            Map.entry("母", "mother"), Map.entry("兄", "brother"), Map.entry("弟", "brother"), Map.entry("姉", "sister"), Map.entry("妹", "sister"), Map.entry("息子", "son"), Map.entry("娘", "daughter"));

    /** The one words for the chains a quote spells out: "my father's mother" is a grandmother, 母の兄 an uncle. */
    private static List<String> spelledOut(String quote) {
        List<String> out = new ArrayList<>();
        for (Matcher m = CHAIN.matcher(quote); m.find(); ) {
            List<String> chain = new ArrayList<>();
            for (String w : m.group().split("['’]s\\s+|の")) { String l = w.strip().toLowerCase(Locale.ROOT); chain.add(STEP_OF.getOrDefault(l, l.replace(' ', '-'))); }
            String one = oneWord(chain);
            if (one != null) out.add(one);
        }
        return out;
    }

    /**
     * Whether a quote speaks of a relative written by these relation words: the words of each step (mother and father), or one word for the whole
     * of it (grandfather), or, for one word, the words for its pair (my parents, for a father) or the chain it stands for (my father's mother).
     */
    static boolean says(String quote, List<String> chain) {
        if (chain.stream().allMatch(w -> saysRelationWord(quote, w, chain.size() == 1))) return true;
        String whole = oneWord(chain);
        if (whole != null && saysRelationWord(quote, whole)) return true;
        String one = chain.size() == 1 ? chain.get(0) : whole;
        return one != null && spelledOut(quote).stream().anyMatch(alike(one)::contains);
    }

    /** The one word for a chain of relation words, where a language has one: mother's father is a grandfather, father's brother an uncle. Null otherwise. */
    static String oneWord(List<String> chain) {
        Set<String> up = Set.of("mother", "father", "parent"), down = Set.of("son", "daughter", "child"), side = Set.of("brother", "sister");
        String last = chain.get(chain.size() - 1);
        if (chain.size() >= 2 && (chain.stream().allMatch(up::contains) || chain.stream().allMatch(down::contains)))
            return "great-".repeat(chain.size() - 2) + "grand" + last;
        if (chain.size() != 2) return null;
        String first = chain.get(0);
        if (up.contains(first) && side.contains(last)) return last.equals("brother") ? "uncle" : "aunt";
        if (side.contains(first) && (last.equals("son") || last.equals("daughter"))) return last.equals("son") ? "nephew" : "niece";
        if ((first.equals("uncle") || first.equals("aunt")) && down.contains(last)) return "cousin";
        return null;
    }

    /**
     * The sentence that leaves a fact out when one of its people is written only by relation words (the writer of notes.txt's mother's father,
     * Endo's son) and the quote does not carry them ({@link #says}). A read keeps the fact when another fact about that relative carries the
     * words ({@link #relativesSaid}). Null when the quote carries them.
     */
    static String relationUnsaid(Fact fact) {
        String quote = fact.quote() == null ? "" : Normalizer.normalize(fact.quote(), Normalizer.Form.NFKC);
        for (String party : List.of(fact.subject(), fact.object())) {
            List<String> chain = relationWords(party);
            if (!chain.isEmpty() && !says(quote, chain)) return leftOut(fact, chain);
        }
        return null;
    }

    private static String leftOut(Fact fact, List<String> chain) {
        String relation = String.join("'s ", chain);
        return "\"" + sentence(fact.subject(), fact.relation(), fact.object()) + "\" was left out, because the words given for it (\"" + Acquisitions.compress(fact.quote(), 60)
                + "\") do not speak of " + ("aeiou".indexOf(relation.charAt(0)) >= 0 ? "an " : "a ") + relation + ".";
    }

    // any word for a relative the lists above know, in English, in the everyday words or in Japanese: words that carry none are in another language
    private static final Pattern ANY_RELATION_WORD = Pattern.compile("(?i)(?<![\\p{L}])(?:(?:great|step|god|half|foster)[- ]?)*(?:grand)?(?:mother|father|parent|son|daughter|child|children|brother|sister|sibling"
            + "|wife|husband|spouse|widow|widower|uncle|aunt|cousin|nephew|niece|mum|mom|mam|mama|mummy|mommy|dad|daddy|papa|gran|granny|grandma|grandpa|grandad|granddad|nan|nana|gramps)s?(?![\\p{L}])"
            + "|父|母|親|祖|孫|息子|娘|長男|長女|次男|次女|兄|弟|姉|妹|叔|伯|甥|姪|妻|夫|嫁|婿|従|いとこ|おじ|おば|じい|ばあ");

    /**
     * A person written only by relation words ("the writer of notes.txt's grandmother", "Tom Ellis's son") keeps the facts the read gives them when
     * the account's words for that relative stand in the quote of at least one of those facts ({@link #says}), or the text writes the person that
     * way: the sentences after the first say she or he, or in Japanese nothing at all, and their facts are that relative's too. When no quote of
     * theirs carries a relation word the lists know (an account in another language), the judge is asked once whether the words speak of that
     * relative. Otherwise each of their facts is left out, and the read says so.
     */
    static List<Fact> relativesSaid(List<Fact> facts, String wholeFlat, Model model, YesNo judge, List<String> dropped) {
        Map<String, List<String>> quotes = new LinkedHashMap<>();
        for (Fact f : facts) for (String party : new LinkedHashSet<>(List.of(f.subject(), f.object())))
            if (!relationWords(party).isEmpty()) quotes.computeIfAbsent(party, k -> new ArrayList<>()).add(Normalizer.normalize(f.quote() == null ? "" : f.quote(), Normalizer.Form.NFKC));
        Map<String, List<String>> unsaid = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : quotes.entrySet()) {
            List<String> chain = relationWords(e.getKey()), qs = e.getValue().stream().distinct().toList();
            if (qs.stream().anyMatch(q -> says(q, chain)) || wholeFlat.contains(flat(e.getKey()))) continue;
            if (qs.stream().noneMatch(q -> ANY_RELATION_WORD.matcher(q).find()) && sure(model, judge, relativeQuestion(qs, e.getKey(), chain))) continue;
            unsaid.put(e.getKey(), chain);
        }
        if (unsaid.isEmpty()) return facts;
        List<Fact> out = new ArrayList<>();
        for (Fact f : facts) {
            List<String> chain = unsaid.containsKey(f.subject()) ? unsaid.get(f.subject()) : unsaid.get(f.object());
            if (chain == null) out.add(f);
            else dropped.add(leftOut(f, chain));
        }
        return out;
    }

    /** The question whether words in a language the lists do not know speak of the relative the reader wrote them as. */
    static String relativeQuestion(List<String> quotes, String label, List<String> chain) {
        String relation = String.join("'s ", chain);
        return "A reader took these words from a family's account: \"" + String.join("\" \"", quotes.subList(0, Math.min(3, quotes.size()))) + "\".\n"
                + "The reader concluded that they speak of " + label + ".\n"
                + "Do the quoted words speak of this " + relation + "? They do when they name the relation in any language, as 祖父 names a grandfather.";
    }

    /**
     * A parent-child fact whose quote calls one of the two a father, a mother, a son or a daughter, and nothing else of that kind,
     * records that person's sex as well, as a claim of its own from the same words. The checks use it: a mother's age, two fathers.
     */
    static Read withSexes(Read read) { return withSexes(read, Set.of()); }

    /** The same, where the facts in {@code refused} give nobody a sex: the owner disputed or retired them. */
    static Read withSexes(Read read, Set<Fact> refused) {
        List<Fact> out = new ArrayList<>(read.facts());
        Set<String> had = new LinkedHashSet<>();
        for (Fact f : read.facts()) if (f.relation().equals("sex")) had.add(f.subject());
        for (Fact f : read.facts()) {
            if (refused.contains(f)) continue;
            String[] s = FamilyKin.sexFromQuote(f.relation(), f.subject(), f.object(), f.quote());
            if (s == null || FamilyQuestions.placeholder(s[0]) || !had.add(s[0] + "\t" + s[1])) continue;
            out.add(new Fact(s[0], "sex", s[1], "", f.quote()));
        }
        return read.with(read.people(), out, read.dropped(), read.twoWays());
    }

    /**
     * What a read may file, decided by arithmetic and not by the model: a parent is older than their child, and two people are not each
     * other's parent. Birth years come from this read and from what the library holds. A relation that cannot be true is left out and named.
     * A stand-in the reader made up for the teller ("the writer of notes.txt") is never another name of a real person.
     */
    static Read possible(LibraryStore store, Read given, String teller) throws IOException {
        Graph g = FamilyPeople.view(store);
        Set<String> familyParts = FamilyMentions.familyParts(g, given);
        Read read = oneNameEach(g, given, familyParts);
        // a party written only by a family name is a family (遠藤家, the Endo family), or somebody known only by that name ("Endo's son"): never a
        // person of that name, which would join the parents of strangers, and never left out. A family is the other party's membership of it; a
        // person is described by who they are to the other party, and the library links them to somebody only by its rule (FamilyMentions)
        Set<String> givenParts = new LinkedHashSet<>();
        for (Person p : read.people()) if (!p.given().isBlank()) { givenParts.add(p.given()); if (p.family().isBlank()) givenParts.add(p.name()); }
        for (NameRead n : read.names()) if (!n.given().isBlank()) givenParts.add(n.given());
        // a family is never a person: a relation to a family is the other party's membership of it (the head of the Morita family, an adoption
        // into the Takahashi family), and a relation between two families files nothing about a person
        Set<String> personParts = FamilyMentions.personFamilyParts(g, read);
        List<String> readFamilies = FamilyMentions.familiesWritten(read);
        List<String> droppedNow = new ArrayList<>(read.dropped());
        Map<Integer, List<Fact>> byIndex = new LinkedHashMap<>();
        Map<String, List<String>> describedByWord = new LinkedHashMap<>();   // the word and the words it stands in → the described people made for it
        Map<String, String> pickedAs = new LinkedHashMap<>();   // the words and the person the model took a described person for → that described person
        List<Fact> all = read.facts();
        for (int i = 0; i < all.size(); i++) {
            Fact f = all.get(i);
            if (personToPerson(f.relation())) {
                // a word the library holds as a family's name that the words write as somebody ("Endo's son") is that somebody, never the family
                boolean marked = f.detail().containsKey(FamilyMentions.MARKED);
                boolean sf = FamilyMentions.familyParty(g, f.subject(), readFamilies, personParts) && !FamilyMentions.writtenAsSomebody(g, f.subject(), f.quote(), marked, teller, familyParts, givenParts);
                // and a family name alone that is the name of a family the read speaks of or the library holds, as the household, the adopter or
                // the one an heir follows: 森田勇 | head-of-household | 森田 is the head of the 森田 family
                boolean of = (FamilyMentions.familyParty(g, f.object(), readFamilies, personParts) || FamilyMentions.familyByName(g, read, f.relation(), f.object()))
                        && !FamilyMentions.writtenAsSomebody(g, f.object(), f.quote(), marked, teller, familyParts, givenParts);
                if (sf && of) { droppedNow.add("\"" + sentence(f.subject(), f.relation(), f.object()) + "\" was left out, because both sides of it are families, and a family is related to another family only as its branch."); continue; }
                if (sf || of) { byIndex.put(i, List.of(FamilyMentions.membership(f, sf))); continue; }
            }
            if (!(personToPerson(f.relation()) || associate(f.relation()))) continue;
            // the words carry only a family part of the party the model named: the party is written as the words write it, and who it is, is the
            // library's rule and the family's answer; the model's reading is a candidate
            Fact only = FamilyMentions.familyPartOnly(g, f, read, familyParts);
            List<Fact> described = only == null ? List.of() : FamilyMentions.describedAll(g, only, teller, familyParts, givenParts);
            if (described.isEmpty()) described = FamilyMentions.describedAll(g, f, teller, familyParts, givenParts);
            if (described.isEmpty()) continue;
            byIndex.put(i, described);
            for (Fact d : described) for (String side : List.of(d.subject(), d.object())) {
                String[] mp = FamilyMentions.parts(side);
                if (mp == null) continue;
                describedByWord.computeIfAbsent(flat(mp[2]) + "\t" + flat(d.quote()), k -> new ArrayList<>()).add(side);
                if (d.detail().containsKey(FamilyMentions.PICKED)) pickedAs.putIfAbsent(flat(d.quote()) + "\t" + d.detail().get(FamilyMentions.PICKED), side);
            }
        }
        describedByWord.replaceAll((k, v) -> new ArrayList<>(new LinkedHashSet<>(v)));
        List<Fact> named = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Fact f = all.get(i);
            if (byIndex.containsKey(i)) { named.addAll(byIndex.get(i)); continue; }
            if (personToPerson(f.relation()) && (FamilyMentions.familyParty(g, f.subject(), readFamilies, personParts) && FamilyMentions.familyParty(g, f.object(), readFamilies, personParts))) continue;
            // a fact of a life the model gave the one it took a described person for, from the same words ("Endo's son … his father kept
            // silkworms"): the described person's, with the model's reading kept as the candidate
            String as = personToPerson(f.relation()) || associate(f.relation()) ? null : pickedAs.get(flat(f.quote()) + "\t" + f.subject());
            if (as != null) { named.add(withDetail(new Fact(as, f.relation(), f.object(), f.date(), f.quote(), FamilyMentions.unmarked(f).detail()), FamilyMentions.PICKED, f.subject())); continue; }
            // a fact of a life whose subject is written only by a family name: the person the same words describe, a family, or somebody of it
            Fact life = FamilyMentions.lifeMention(g, f, teller, familyParts, givenParts, describedByWord);
            named.add(life != null ? life : FamilyMentions.unmarked(f));
        }
        read = read.with(read.people(), named, droppedNow, read.twoWays());
        // by node id: a born-on date, or the year a born-in claim ends with in brackets, as the tree import and the reader write a birth with its place
        Map<String, FamilyDate> bornIn = new LinkedHashMap<>(Gedcom.birthDates(g, store.scanFindings().findings()));
        Function<String, String> idOf = g::nodeIdOf;
        // a read keeps every dated fact the source gives, an older claim notwithstanding: a birth year that makes somebody younger than their
        // own child is the view's to weigh, and the family's view sets the parent claim aside with its reason ({@link FamilyDoubts})
        for (Fact f : read.facts()) {
            FamilyDate d = f.relation().equals("born-on") ? FamilyDate.parse(f.object()) : f.relation().equals("born-in") ? FamilyDate.parse(f.date()) : null;
            if (d != null) bornIn.put(idOf.apply(f.subject()), d);
        }
        // who is whose parent, as the library already has it and as this read says it: parent id → child id
        Set<String> parentOf = new LinkedHashSet<>();
        for (Graph.Edge e : g.edges()) { if (e.disputed()) continue; if (e.predicate().equals("parent-of")) parentOf.add(e.from() + "\t" + e.to()); else if (e.predicate().equals("child-of") || e.predicate().equals("adopted-by")) parentOf.add(e.to() + "\t" + e.from()); }
        Set<String> inLibrary = new LinkedHashSet<>(parentOf);
        List<Fact> kept = new ArrayList<>();
        List<String> dropped = new ArrayList<>(read.dropped());
        for (Fact f : read.facts()) {
            boolean down = f.relation().equals("parent-of"), up = f.relation().equals("child-of") || f.relation().equals("adopted-by");
            if (!down && !up) {
                // two names of a read that are one person to the library cannot be each other's relatives
                if (personToPerson(f.relation()) && !f.subject().isBlank() && idOf.apply(f.subject()).equals(idOf.apply(f.object()))) { dropped.add("\"" + sentence(f.subject(), f.relation(), f.object()) + "\" was left out, because it makes a person their own relative."); continue; }
                kept.add(f); continue;
            }
            String parent = idOf.apply(down ? f.subject() : f.object()), child = idOf.apply(down ? f.object() : f.subject());
            String parentName = down ? f.subject() : f.object(), childName = down ? f.object() : f.subject();
            FamilyDate pb = bornIn.get(parent), cb = bornIn.get(child);
            if (parent.equals(child)) { dropped.add("\"" + sentence(f.subject(), f.relation(), f.object()) + "\" was left out, because it makes a person their own parent."); continue; }
            if (pb != null && cb != null && pb.earliest() > cb.latest() - PARENT_GAP) { dropped.add("\"" + sentence(f.subject(), f.relation(), f.object()) + "\" was left out, because it cannot be true: " + parentName + " was born " + pb.in() + " and " + childName + " " + cb.in() + "."); continue; }
            if (parentOf.contains(child + "\t" + parent)) {
                dropped.add("\"" + sentence(f.subject(), f.relation(), f.object()) + "\" was left out, because " + (inLibrary.contains(child + "\t" + parent)
                        ? "your library already has it the other way round (" + childName + " as the parent of " + parentName + "), and no birth years say which is right. If this one is right, dispute the other one and read the text again."
                        : "this text also says it the other way round (" + childName + " as the parent of " + parentName + "), and no birth years say which is right. The other one was kept; if this one is right, dispute the other one and read the text again."));
                continue;
            }
            parentOf.add(parent + "\t" + child);
            kept.add(f);
        }
        String tellerFlat = flat(teller);
        List<Person> people = new ArrayList<>();
        for (Person p : read.people()) {
            // the library keeps a person's other names in one line with commas between them: a name written "Family, Given" would come back as
            // two names of one word each, which strangers share. Its comma is taken out
            List<String> also = new ArrayList<>();
            for (String a : p.also()) also.add(a.replaceAll("\\s*[,，、]\\s*", " ").strip());
            also.removeIf(a -> FamilyQuestions.placeholder(a) || (!tellerFlat.isEmpty() && flat(a).contains(tellerFlat)) || !FamilyNames.keepAsOtherName(p.name(), a));
            people.add(new Person(p.name(), p.reading(), also, p.family(), p.given()));
        }
        return read.with(people, kept, dropped, read.twoWays());
    }

    /**
     * Each person under ONE name: the name the library already has them under, else the first way this read writes them. Hisa Endō
     * from a tree site and "Endo, Hisa" from a book's index are one man, and the way that was set aside is kept as another name of his.
     */
    static Read oneNameEach(Graph g, Read read) { return oneNameEach(g, read, FamilyMentions.familyParts(g, read)); }

    /**
     * The same. {@code familyParts}: the family names the library and the read know ({@link FamilyMentions#familyParts}), by which a name
     * whose parts the read does not give is split. A given name alone ("Kenji", 健二) in the same read as exactly one full name with that
     * given part, whose birth years do not clash, is that person; with two such names nothing is joined.
     */
    static Read oneNameEach(Graph g, Read read, Set<String> familyParts) {
        Map<String, String> known = FamilyNames.known(g), chosen = new LinkedHashMap<>(), firstByKey = new LinkedHashMap<>();
        Set<String> related = related(read);
        Map<String, String> across = acrossScripts(g, known, read, related);
        Function<String, String> base = name -> chosen.computeIfAbsent(name, n0 -> {
            String n = across.getOrDefault(n0, n0);
            String held = FamilyNames.resolve(g, known, n);
            if (!held.equals(n)) return held;
            for (String k : FamilyNames.keys(n)) { String first = firstByKey.putIfAbsent(k, n); if (first != null && !first.equals(n)) return first; }
            return n;
        });
        Map<String, String> alone = givenAlone(read, familyParts, base, related);
        Function<String, String> one = name -> {
            String full = alone.get(name);
            if (full == null) return base.apply(name);
            String to = base.apply(full);
            chosen.put(name, to);
            return to;
        };
        List<Fact> facts = new ArrayList<>();
        for (Fact f : read.facts()) facts.add(new Fact(one.apply(f.subject()), f.relation(), personToPerson(f.relation()) || associate(f.relation()) ? one.apply(f.object()) : f.object(), f.date(), f.quote(), f.detail()));
        Map<String, Person> people = new LinkedHashMap<>();
        for (Person p : read.people()) {
            String name = one.apply(p.name());
            List<String> also = new ArrayList<>(p.also());
            if (!name.equals(p.name())) also.add(p.name());
            // the parts of a name written in another script are that spelling's parts, not the parts of the name the person is filed under
            boolean ownParts = name.equals(p.name()) || FamilyForms.script(p.name()).equals(FamilyForms.script(name));
            people.merge(name, new Person(name, p.reading(), also, ownParts ? p.family() : "", ownParts ? p.given() : ""), (a, b) -> new Person(name, a.reading().isEmpty() ? b.reading() : a.reading(), union(a.also(), b.also()), a.family().isEmpty() ? b.family() : a.family(), a.given().isEmpty() ? b.given() : a.given()));
        }
        // a name that was set aside and has no entry of its own among the people still becomes another name of the person
        chosen.forEach((from, to) -> { if (!from.equals(to)) people.merge(to, new Person(to, "", List.of(from)), (a, b) -> new Person(to, a.reading(), union(a.also(), b.also()), a.family(), a.given())); });
        List<String[]> twoWays = new ArrayList<>();
        for (String[] t : read.twoWays()) twoWays.add(new String[]{one.apply(t[0]), t[1], t[2]});
        // a name another name of the person is filed under the person the facts are filed under
        List<NameRead> names = new ArrayList<>();
        for (NameRead n : read.names()) names.add(new NameRead(one.apply(n.person()), n.name(), n.family(), n.given(), n.forms(), n.kind(), n.said(), n.date(), n.quote()));
        return new Read(new ArrayList<>(people.values()), facts, read.dropped(), twoWays, names, read.families());
    }

    /**
     * The people of a read written once in characters and once in Latin letters, where the read itself gives the two as one person's: the text
     * writes one in the brackets right after the other ("Morita Kenji (森田健二, もりた けんじ)"), or the model gives the one among the other's
     * other spellings ({@link #paired}). That name in Latin letters is that person, filed under the name in characters. A spelling of a
     * reading alone joins nobody: two entries, and the family is asked whether they are one person. Name as written → the name to file both
     * under: the one the library already holds, else the name in characters. Two entries the library already holds apart are never joined here.
     */
    static Map<String, String> acrossScripts(Graph g, Map<String, String> known, Read read) { return acrossScripts(g, known, read, related(read)); }

    /**
     * Two names the read relates by a fact ("His grandson Morita Kenji", relative-of 森田健二; "John's son John Hale"), as pairs of the names as
     * written: the read says they are two people, and no spelling or given name joins them.
     */
    static Set<String> related(Read read) {
        Set<String> out = new LinkedHashSet<>();
        for (Fact f : read.facts()) if ((personToPerson(f.relation()) || associate(f.relation())) && !f.subject().equals(f.object())) { out.add(f.subject() + "\t" + f.object()); out.add(f.object() + "\t" + f.subject()); }
        return out;
    }

    /**
     * Whether the read itself gives two ways of writing as one person's: the model gives the one among the other's other spellings, as its
     * reading, or as a name that person carried ({@code names}), or a quote of the read writes the one in the brackets right after the other
     * ("Morita Kenji (森田健二, もりた けんじ)", 健二（森田健二）).
     */
    static boolean paired(Read read, String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) return false;
        for (Person p : read.people()) {
            List<String> ways = new ArrayList<>(p.also());
            if (!p.reading().isBlank()) ways.add(p.reading());
            String other = p.name().equals(a) ? b : p.name().equals(b) ? a : null;
            if (other != null && ways.stream().anyMatch(x -> sameWay(x, other))) return true;
        }
        for (NameRead n : read.names()) {
            String other = n.person().equals(a) ? b : n.person().equals(b) ? a : null;
            if (other != null && (sameWay(n.name(), other) || n.forms().stream().anyMatch(x -> sameWay(x, other)))) return true;
        }
        Set<String> quotes = new LinkedHashSet<>();
        for (Fact f : read.facts()) quotes.add(f.quote());
        for (NameRead n : read.names()) quotes.add(n.quote());
        for (FamilyRead f : read.families()) quotes.add(f.quote());
        Set<String> words = nameWords(read);
        for (String q : quotes) if (!bracketed(q, a, b, words).isEmpty() || !bracketed(q, b, a, words).isEmpty()) return true;
        return false;
    }

    /**
     * Two ways of writing that are the same: the same letters, apart from accents, spaces and punctuation, or the same form of one name in one
     * script. A reading in kana is not the same way as its romaji: that the two spell one sound says nothing of whether they are one person.
     */
    private static boolean sameWay(String x, String y) {
        if (x == null || y == null) return false;
        if (!flat(x).isEmpty() && flat(x).equals(flat(y))) return true;
        return FamilyForms.script(x).equals(FamilyForms.script(y)) && FamilyForms.sameForm(x.strip(), y.strip());
    }

    /** Text in one form for finding a name in it with its brackets: modern width, and Latin letters without accents. */
    private static String plain(String s) {
        return Normalizer.normalize(Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKD).replaceAll("(?<=\\p{IsLatin})\\p{M}+", ""), Normalizer.Form.NFKC);
    }

    /**
     * The ways of writing in the brackets right after {@code outer} in the words, where one of them is {@code inner} ("" for any): the items
     * of "Morita Kenji (森田健二, もりた けんじ)" after Morita Kenji. Empty when the words do not write {@code outer} with such brackets. A name in
     * Latin letters counts only as the whole name before the brackets: "Kenji" is not the name before "(森田健二)" in "Morita Kenji (森田健二)".
     * A capitalised word that begins the sentence is a word of the name only when it is one of {@code nameWords} ({@link #nameWords}): "Then
     * Shōji (森田正二)" writes Shōji.
     */
    static List<String> bracketed(String words, String outer, String inner, Set<String> nameWords) {
        if (words == null || outer == null || outer.isBlank()) return List.of();
        String w = plain(words), o = plain(outer.replaceAll("\\s*[(（][^)）]*[)）]\\s*$", "").strip());
        if (o.isEmpty()) return List.of();
        boolean latin = FamilyForms.script(o).equals("latin");
        Matcher m = Pattern.compile((latin ? "(?<![\\p{L}'’-])" : "") + Pattern.quote(o) + "\\s*[(（]([^)）]*)[)）]", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(w);
        while (m.find()) {
            if (latin) {
                // the word before, with only a space between, is a word of the same name when it begins with a capital (Morita Kenji, not Kenji)
                Matcher before = Pattern.compile("(\\p{L}[\\p{L}'’-]*)[ \\t]+$").matcher(w.substring(0, m.start()));
                if (before.find() && Character.isUpperCase(before.group(1).codePointAt(0)) && !before.group(1).matches("(?i)the|a|an|his|her|their|our|my|its")
                        && (nameWords.contains(before.group(1).toLowerCase(Locale.ROOT)) || !w.substring(0, before.start()).matches("(?s)(?:.*[.!?;:。！？\\n])?\\s*"))) continue;
            }
            List<String> items = new ArrayList<>();
            for (String item : m.group(1).split("[,，、;；/]")) if (!item.isBlank()) items.add(item.strip());
            if (inner == null || inner.isBlank() || items.stream().anyMatch(x -> sameWay(x, inner))) return items;
        }
        return List.of();
    }

    /** The words of the names a read writes in Latin letters, in lower case without accents. */
    static Set<String> nameWords(Read read) {
        Set<String> out = new HashSet<>();
        for (String n : FamilyMentions.fullNames(read)) if (FamilyForms.script(n).equals("latin")) for (String x : n.split("[\\s,]+")) if (!x.isBlank()) out.add(plain(x).toLowerCase(Locale.ROOT));
        return out;
    }

    /** The birth years a read gives its people, by the name as written. */
    private static Map<String, FamilyDate> bornInRead(Read read) {
        Map<String, FamilyDate> born = new LinkedHashMap<>();
        for (Fact f : read.facts()) {
            FamilyDate d = f.relation().equals("born-on") ? FamilyDate.parse(f.object()) : f.relation().equals("born-in") ? FamilyDate.parse(f.date()) : null;
            if (d != null) born.putIfAbsent(f.subject(), d);
        }
        return born;
    }

    /** The same, where two names the read relates by a fact ({@link #related}) or gives birth years apart are never joined: a grandson named after his grandfather is his grandson. */
    static Map<String, String> acrossScripts(Graph g, Map<String, String> known, Read read, Set<String> related) {
        Map<String, FamilyDate> born = bornInRead(read);
        List<String> hans = new ArrayList<>();
        for (Person p : read.people()) if (FamilyForms.script(p.name()).equals("han")) hans.add(p.name());
        Map<String, String> out = new LinkedHashMap<>();
        for (Person p : read.people()) {
            if (!FamilyForms.script(p.name()).equals("latin") || FamilyNames.keys(p.name()).isEmpty()) continue;
            Set<String> whose = new LinkedHashSet<>();
            for (String han : hans) if (paired(read, p.name(), han)) whose.add(han);
            if (whose.size() != 1) continue;
            String han = whose.iterator().next();
            if (related.contains(p.name() + "\t" + han)) continue;
            FamilyDate a = born.get(p.name()), b = born.get(han);
            if (a != null && b != null && FamilyDate.apart(a, b, 1)) continue;
            String hanHeld = FamilyNames.resolve(g, known, han), latinHeld = FamilyNames.resolve(g, known, p.name());
            boolean hanIn = g.node(g.nodeIdOf(hanHeld)) != null, latinIn = g.node(g.nodeIdOf(latinHeld)) != null;
            if (hanIn && latinIn && !g.nodeIdOf(hanHeld).equals(g.nodeIdOf(latinHeld))) continue;
            String to = hanIn ? hanHeld : latinIn ? latinHeld : han;
            out.put(p.name(), to);
            out.put(han, to);
        }
        return out;
    }

    /**
     * The given names alone of a read that are one full name of the same read: name as written → that full name as written. A full name's
     * given part is what the read gives as its {@code given} (for the person, or for one of their names), else the rest of the name after a
     * family part the library or the read knows. A given name alone is joined only to the one full name with that given part (spellings of
     * one name count as one), only when the read itself gives the two as one person's ({@link #paired}: the model gives the given name among
     * the person's other spellings, or the text writes one in brackets after the other), and only when the birth years the read gives do not
     * clash. Otherwise the two are two entries, and the family is asked whether they are one person.
     */
    static Map<String, String> givenAlone(Read read, Set<String> familyParts, Function<String, String> one) { return givenAlone(read, familyParts, one, related(read)); }

    /** The same, where a given name alone that a fact of the read relates to the full name ({@link #related}) is another person: "Kenji's grandson Morita Kenji". */
    static Map<String, String> givenAlone(Read read, Set<String> familyParts, Function<String, String> one, Set<String> related) {
        Map<String, Set<String>> givenOf = new LinkedHashMap<>();   // full name → its given parts
        for (Person p : read.people()) if (FamilyMentions.full(p.name()) && !p.given().isBlank()) givenOf.computeIfAbsent(p.name(), k -> new LinkedHashSet<>()).add(p.given());
        for (NameRead n : read.names()) if (!n.given().isBlank() && FamilyMentions.full(n.person())) givenOf.computeIfAbsent(n.person(), k -> new LinkedHashSet<>()).add(n.given());
        for (String n : FamilyMentions.fullNames(read)) {
            String part = givenBySplit(n, familyParts);
            if (!part.isEmpty()) givenOf.computeIfAbsent(n, k -> new LinkedHashSet<>()).add(part);
        }
        Set<String> alone = new LinkedHashSet<>();
        for (Person p : read.people()) alone.add(p.name());
        for (Fact f : read.facts()) { alone.add(f.subject()); if (personToPerson(f.relation()) || associate(f.relation())) alone.add(f.object()); }
        for (NameRead n : read.names()) alone.add(n.person());
        alone.removeIf(n -> n == null || FamilyMentions.word(n) == null);
        Map<String, FamilyDate> born = new LinkedHashMap<>();
        for (Fact f : read.facts()) {
            FamilyDate d = f.relation().equals("born-on") ? FamilyDate.parse(f.object()) : f.relation().equals("born-in") ? FamilyDate.parse(f.date()) : null;
            if (d != null) born.putIfAbsent(f.subject(), d);
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String w : alone) {
            if (givenOf.containsKey(w)) continue;
            Set<String> whose = new LinkedHashSet<>();
            String first = null;
            for (Map.Entry<String, Set<String>> e : givenOf.entrySet()) {
                if (e.getKey().equals(w) || e.getValue().stream().noneMatch(x -> FamilyForms.sameForm(x, w))) continue;
                if (whose.add(one.apply(e.getKey())) && first == null) first = e.getKey();
            }
            if (whose.size() != 1 || related.contains(w + "\t" + first) || !(paired(read, w, first) || paired(read, w, whose.iterator().next()))) continue;
            FamilyDate a = born.get(w), b = born.get(first);
            if (a != null && b != null && FamilyDate.apart(a, b, 1)) continue;
            out.put(w, first);
        }
        return out;
    }

    /** A full name's given part by a family part the library or the read knows: the rest after it, or in Latin letters the one other word; "" when none is known. */
    static String givenBySplit(String name, Set<String> familyParts) {
        String sc = FamilyForms.script(name);
        if (sc.equals("latin")) {
            String[] w = name.replace(",", " ").strip().split("\\s+");
            if (w.length != 2) return "";
            boolean f0 = FamilyMentions.known(w[0], familyParts), f1 = FamilyMentions.known(w[1], familyParts);
            return f0 == f1 ? "" : f0 ? w[1] : w[0];
        }
        if (sc.equals("han") || sc.equals("kana")) {
            String joined = name.replaceAll("[\\s　・]+", "");
            String best = "";
            for (String p : familyParts) {
                if (p == null || !FamilyForms.script(p).equals(sc)) continue;
                String k = p.replaceAll("[\\s　・]+", "");
                boolean begins = sc.equals("han") ? FamilyForms.hanKey(joined).startsWith(FamilyForms.hanKey(k)) : FamilyForms.kanaKey(joined).startsWith(FamilyForms.kanaKey(k));
                if (begins && k.length() < joined.length() && k.length() > best.length()) best = k;
            }
            return best.isEmpty() ? "" : joined.substring(best.length());
        }
        return "";
    }

    /**
     * The new date says no more than the one held: every year it allows is one the new date allows too, and a month or a day only where the held one has the same. The
     * parts are read from the written date: a month as "8月" or "-08", a day as "1日" or "-01".
     */
    static boolean sameDateCoarser(FamilyDate mine, FamilyDate held) {
        // every year the held one allows is in the new one: "BET 1850 AND 1860" says no more than 1855, and "1850" more than "about 1850"
        if (held == null || mine.earliest() > held.earliest() || mine.latest() < held.latest()) return false;
        int[] m = monthDay(mine.written()), h = monthDay(held.written());
        if (m[0] > 0 && m[0] != h[0]) return false;
        if (m[1] > 0 && m[1] != h[1]) return false;
        return true;
    }

    static int[] monthDay(String written) {
        String t = Normalizer.normalize(written == null ? "" : written, Normalizer.Form.NFKC);
        int month = 0, day = 0;
        Matcher jp = Pattern.compile("(\\d{1,2})月(?:\\s*(\\d{1,2})日)?").matcher(t);
        Matcher iso = Pattern.compile("\\d{4}-(\\d{2})(?:-(\\d{2}))?").matcher(t);
        if (jp.find()) { month = Integer.parseInt(jp.group(1)); if (jp.group(2) != null) day = Integer.parseInt(jp.group(2)); }
        else if (iso.find()) { month = Integer.parseInt(iso.group(1)); if (iso.group(2) != null) day = Integer.parseInt(iso.group(2)); }
        return new int[]{month, day};
    }

    /** A fact as a sentence a person reads: "John Ellis was born in 1850", "Mary Ellis is a child of John Ellis". */
    public static String sentence(String subject, String relation, String object) {
        boolean yearOnly = object != null && object.strip().matches("(?i)(about |abt\\.? |before |bef\\.? |after |aft\\.? |est\\.? |cal\\.? |(bet\\.?|between) \\d{3,4} and )?\\d{3,4}");
        boolean qualified = yearOnly && !object.strip().matches("\\d{3,4}");   // "was born about 1850", not "born in about 1850"
        String verb = switch (relation) {
            case "parent-of" -> "is a parent of";
            case "child-of" -> "is a child of";
            case "married-to" -> "was married to";
            case "adopted-by" -> "was adopted by";
            case "step-parent-of" -> "is a step-parent of";
            case "foster-child-of" -> "was a foster child of";
            case "sex" -> "is recorded as";
            case "sibling-of" -> "is a brother or sister of";
            case "relative-of" -> "is a relative of";
            case "head-of-household" -> "was the head of the household of";
            case "born-on" -> yearOnly ? (qualified ? "was born" : "was born in") : "was born on";
            case "died-on" -> yearOnly ? (qualified ? "died" : "died in") : "died on";
            case "born-in" -> "was born in";
            case "died-in" -> "died in";
            case "lived-in" -> "lived in";
            case "migrated-to" -> "moved to";
            case "buried-in" -> "is buried in";
            case "baptised-in" -> "was baptised in";
            case "baptised-on" -> yearOnly ? "was baptised in" : "was baptised on";
            case "occupation" -> "worked as";
            case "informant-for" -> "gave the details for a record about";
            case "witness-for" -> "was a witness at an event of";
            case "godparent-of" -> "was a godparent of";
            case "life-event" -> "—";
            case "aged" -> "was aged";
            case "has-name" -> "was named";
            case "member-of" -> "belonged to";
            case "parent-in-law-of" -> "is a parent-in-law of";
            case "heir-of" -> "was the heir of";
            case "branch-of" -> "is a branch of";
            case "family-seat" -> "had its seat in";
            case "founded-by" -> "was founded by";
            default -> relation.replace('-', ' ');
        };
        return subject + (verb.equals("—") ? ": " : " " + verb + " ") + (relation.equals("has-name") ? FamilyNameHistory.bare(object) : object);
    }

    /**
     * A fact as a sentence, with what its reading adds in words: how a name came ("森田健二 was named 遠藤健二 at birth"), how somebody entered
     * a family ("森田健二 entered 森田 family as 婿養子 (adopted and married)"), the kind of an adoption, whose family name a couple took.
     */
    public static String sentence(String subject, String relation, String object, Map<String, String> detail) {
        if (detail == null || detail.isEmpty()) return sentence(subject, relation, object);
        String kind = detail.getOrDefault("kind", "");
        return switch (relation) {
            case "has-name" -> FamilyNameHistory.claimSentence(subject, FamilyNameHistory.bare(object), kind, "").replaceFirst("\\.$", "");
            case "member-of" -> FamilyHouses.sentence(subject, object, detail);
            case "adopted-by" -> sentence(subject, relation, object) + (kind.equals("mukoyoshi") ? " as 婿養子 (adopted and married)" : kind.equals("heir") ? " as heir" : "");
            case "married-to" -> sentence(subject, relation, object) + (detail.getOrDefault("how", "").equals("nyufu") ? " by 入夫 marriage" : "")
                    + switch (detail.getOrDefault("took", "")) { case "wife" -> ", and they took the wife's family name"; case "husband" -> ", and they took the husband's family name"; case "each-kept" -> ", and each kept their own family name"; default -> ""; };
            default -> sentence(subject, relation, object);
        };
    }

    /** A relation between two people of a family. */
    public static boolean isKinship(String relation) { return personToPerson(relation); }

    /**
     * Somebody a record names who is not its subject: the informant who reported a death, a witness, a godparent. A person, and a lead to
     * the family, but not a relative: these relations never move a birth year or a line of the tree.
     */
    public static boolean associate(String relation) {
        return switch (relation) { case "informant-for", "witness-for", "godparent-of" -> true; default -> false; };
    }

    static boolean personToPerson(String relation) {
        return switch (relation) { case "parent-of", "child-of", "married-to", "adopted-by", "step-parent-of", "foster-child-of", "head-of-household", "sibling-of", "relative-of", "parent-in-law-of", "heir-of" -> true; default -> false; };
    }

    /** The sentence the example answer quotes for a name and a family. */
    private static final String EXAMPLE = "山田太郎（旧姓 遠藤）は大正5年に山田家の養子となった。";

    static String prompt(String piece, String teller, List<Vocabulary.Term> relations) { return prompt(piece, teller, relations, ""); }

    /**
     * The same, for a text whose source is not its teller: {@code source} is a web page's title and host. The page's title names the person
     * the page is about, whom the page's facts are written under by name; "" for a family's own account.
     */
    static String prompt(String piece, String teller, List<Vocabulary.Term> relations, String source) {
        StringBuilder r = new StringBuilder();
        // a person's sex comes with the person (`sex`), kept only where the text's words say it, not as a relation of the list
        for (Vocabulary.Term t : relations) if (!t.slug().equals("sex")) r.append("- ").append(t.slug()).append(": the subject ").append(t.description()).append(" the object\n");
        String opening = source == null || source.isBlank() ? "Below is part of a family's own account, told by " + teller + ". List the people in it and what it says about them.\n\n"
                : "Below is part of a web page, " + source + ". List the people in it and what it says about them. The page's title names the person the page is about: write that person by the name the page gives them.\n\n";
        return opening
                + "Write each person's name as the account writes it. When the account writes a name both in characters and in Latin letters, give the characters as the name and the Latin letters in `also`. A name the account writes only in Latin letters stays in Latin letters. Where the account says I, me or my, the person is "
                + teller + ". A relative the account does not name is written with the account's own words for them, after the person they belong to: \"my grandmother\" is \"" + teller + "'s grandmother\". "
                + "The relation words are English words, whatever the language of the account: 祖母 is grandmother, 父 is father. "
                + "A relative the account names is written by that name, also where it calls them by their relation to " + teller + ". "
                + "A household register, a family list or the family section of a biography writes its members by given name only (長男 正一, 妻 ハル): give each the family name of the household they are listed in (髙橋正一) — "
                + "a child, a brother or a sister the family name of the person the list is about, a wife her husband's (髙橋ハル), with her name under her father's family name (山本ハル) in `also` when the text names her father. "
                + "A date belongs to the event it is written with: the year of an adoption or a marriage is not a birth year. "
                + "When the account has two different people of the same name, tell them apart in brackets after the name, by a birth year or a relation: \"John Ellis (born 1810)\" and \"John Ellis (his son)\". "
                + "`reading` is how the name is read aloud when the account gives it (a kana or romanised form); `also` is any other spelling of the same name the account uses: a person written in two scripts (キャロライン・ヒッチ and Caroline Hitch), or under a title, is ONE person with the other forms in `also`. "
                + "People may carry several names in a life. Give each person's `family` and `given` parts. "
                + "Give each person's `sex` (female or male) where the account's words show it (she, his, a wife, a son, 妻, 長男), else \"\". "
                + "Give each other name in `names`: `person`, `name`, `family`, `given`, other spellings in `also`, `kind` (birth, marriage, adoptive, mukoyoshi, nyufu, succession, legal, taken-back, religious, art, aka, imposed, farm, unknown), "
                + "the account's own words for how it came in `said`, the `date` the account gives for the change, and the `quote`. "
                + "A family the account speaks of as a family (the Morita family, 森田家, the Endos) goes in `families`, with its `seat` when named; a person's entry into it or departure from it is a member-of fact with "
                + "`how` (birth, marriage, adoption, mukoyoshi, nyufu, succession), `left` (marriage-out, adoption-out, branch, death) and `role` (head, heir) as the account says them. "
                + "The head of a family, its heir and an adoption into a family as a whole are member-of facts with that `role` or `how`; every other relation has a person on both sides. "
                + "An adoption is adopted-by with `kind` (ordinary, mukoyoshi, heir); a husband's or wife's parent is parent-in-law-of; a child the account calls only a son or a daughter is child-of. "
                + "A party the account names by a family name alone (\"Endo's son\") is written exactly as the account writes it, also when another sentence names that person in full, and given in `people` with that word as its `family`, and its fact gets \"only_family_name\": true. "
                + "When a record says outright that a parent or a husband or wife is unknown (父不詳, father unknown), give that relation with the object \"(unknown: <the record's words>)\"; when a table of the record leaves that column empty, give it with the object \"(blank)\". "
                + "A record also names people who are not its subject: the person who reported a birth or a death (informant-for), a witness (witness-for), a godparent (godparent-of). "
                + "Give each of them that relation to the person the record is about; the birth, the death and the burial in the record belong to the person the record is about.\n\n"
                + "A fact uses one of these relations, and the direction matters:\n" + r
                + "\nFor born-in, died-in, lived-in, migrated-to and buried-in the object is the place, written as the account writes it. For occupation it is the work. "
                + "A life is more than a birth and a death: for every family member give each dated or notable thing the account tells of their life as a life-event, one fact per event, the object saying what happened in a few of the account's own words (陸軍中将に昇進, founded a trading company, 勲一等瑞宝章), the date in `date`. "
                + "When the account gives a birth or a death with a date and no place, use born-on or died-on, with the date as the object, written as the account writes it. "
                + "When it gives a person's age at some date (a census line, a death, a ship's list, 享年), use aged, with the age as the object exactly as written (42, 42歳, 数え年5歳, 享年73, 3 months) and the date of the record or the event in `date`. "
                + "`date` is the date exactly as the account writes it (明治40年, about 1885, 1907), or \"\" when it gives none. "
                + "`quote` is the sentence of the account that says this, copied exactly.\n\n"
                + "Answer with JSON only, in this shape:\n"
                + "{\"people\": [{\"name\": \"山田太郎\", \"reading\": \"やまだ たろう\", \"also\": [\"Taro Yamada\"], \"family\": \"山田\", \"given\": \"太郎\", \"sex\": \"male\"}],\n"
                + " \"facts\": [{\"subject\": \"山田太郎\", \"relation\": \"born-in\", \"object\": \"広島県安芸郡\", \"date\": \"明治40年\", \"quote\": \"太郎は明治40年に広島県安芸郡で長男として生まれた。\"}],\n"
                + " \"names\": [{\"person\": \"山田太郎\", \"name\": \"遠藤太郎\", \"family\": \"遠藤\", \"given\": \"太郎\", \"kind\": \"birth\", \"said\": \"旧姓\", \"date\": \"\", \"quote\": \"" + EXAMPLE + "\"}],\n"
                + " \"families\": [{\"name\": \"山田\", \"written\": \"山田家\", \"seat\": \"\", \"quote\": \"" + EXAMPLE + "\"}]}\n\n"
                + "THE ACCOUNT:\n" + piece;
    }

    /** Paragraphs gathered into pieces a model reads whole; a paragraph longer than a piece is cut at a sentence end. */
    static List<String> pieces(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder now = new StringBuilder();
        for (String para : text.split("\\n\\s*\\n")) {
            String p = para.strip();
            if (p.length() > CHUNK && now.length() > 0) { out.add(now.toString().strip()); now.setLength(0); }   // what came before a long paragraph stays before it
            while (p.length() > CHUNK) {
                int cut = Math.max(Math.max(p.lastIndexOf("。", CHUNK), p.lastIndexOf(". ", CHUNK)), CHUNK / 2);
                out.add(p.substring(0, cut + 1)); p = p.substring(cut + 1).strip();
            }
            if (now.length() + p.length() > CHUNK && now.length() > 0) { out.add(now.toString().strip()); now.setLength(0); }
            if (!p.isEmpty()) now.append(p).append("\n\n");
        }
        if (now.length() > 0) out.add(now.toString().strip());
        return out;
    }

    /**
     * Whether the text says the words quoted for a fact. A sentence is one run of the text; a register or a list says a thing across
     * two lines (the head of the household on one, the wife on the next), so a quote of several lines holds when every line of it is there.
     */
    static boolean said(String wholeFlat, String quote) {
        if (flat(quote).length() < 3) return false;
        if (wholeFlat.contains(flat(quote))) return true;
        String[] parts = quote.split("\\R|…|\\.{3}|\\s/\\s|\\s\\|\\s");
        if (parts.length < 2) return false;
        int held = 0;
        for (String part : parts) { String f = flat(part); if (f.isEmpty()) continue; if (f.length() < 2 || !wholeFlat.contains(f)) return false; held++; }
        return held >= 2;
    }

    // a Latin letter loses its accent (a book writes Endo, a tree Endō); kana keep their marks
    static String flat(String s) { return s == null ? "" : Normalizer.normalize(Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("(?<=\\p{IsLatin})\\p{M}+", ""), Normalizer.Form.NFKC).replaceAll("[\\s\\p{Punct}「」『』、。“”‘’]+", "").toLowerCase(Locale.ROOT); }

    private static List<String> union(List<String> a, List<String> b) { Set<String> s = new LinkedHashSet<>(a); s.addAll(b); return new ArrayList<>(s); }
}
