package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
/**
 * GEDCOM in and out, for the genealogy profile. Import turns INDI records into person nodes and
 * FAM records plus the events into DRAFT findings — one edge each, with the GEDCOM file as the source
 * (a file the person shelved: the personal tier) — so a tree arrives on the same footing as any other
 * claim: reviewable, disputable, cited. Nothing is accepted by import. Whether a person may be living is
 * {@link FamilyLiving}'s rule over the library's claims, the file's among them. A restriction the file writes on a person
 * ({@code 1 RESN}) is read and left out: who may see the library is decided by who may read it.
 *
 * <p>Each INDI record is one person: two records of one name (a father and a son, two sisters where the second was named after
 * the first) become two people, told apart by a birth year or by the record's own number in the file. The file's encoding is
 * read from its header (ANSEL, the Windows and DOS code pages, MacRoman, UTF-16), every name form is kept, and what the library
 * does not read is listed rather than dropped in silence. A newer copy of the same file corrects its own earlier drafts.
 *
 * <p>Export walks the family from a person and writes GEDCOM 5.5.1 for anything else to read: everyone in the family, the living
 * too, with the sources of each claim, drafts marked as such, and disputed claims left out.
 */
public final class Gedcom {

    /** A person whom no date places more than this many years back may still be living. */
    public static final int LIVING_YEARS = 110;

    /**
     * {@code superseded}: drafts from an earlier copy of the file that this copy corrects. {@code apart}: sentences about people of
     * one name who were kept apart, for the person to read.
     */
    /**
     * {@code superseded}: drafts of an earlier copy this copy says something else about; {@code moved}: drafts it says again of a person
     * it now names apart. {@code mayBeLiving}: the file's people whom nothing in the library places in the past, once the file is in.
     * {@code names}: the name claims the import wrote or backed; {@code houses}: the families the file describes as families ({@code _HOUSE});
     * {@code members}: the memberships of those families it filed.
     */
    public record Outcome(int persons, int families, int findings, int mayBeLiving, List<String> problems, int superseded, List<String> apart, int moved,
                          int names, int houses, int members) {
        public Outcome(int persons, int families, int findings, int mayBeLiving, List<String> problems) { this(persons, families, findings, mayBeLiving, problems, 0, List.of(), 0); }
        public Outcome(int persons, int families, int findings, int mayBeLiving, List<String> problems, int superseded, List<String> apart) { this(persons, families, findings, mayBeLiving, problems, superseded, apart, 0); }
        public Outcome(int persons, int families, int findings, int mayBeLiving, List<String> problems, int superseded, List<String> apart, int moved) { this(persons, families, findings, mayBeLiving, problems, superseded, apart, moved, 0, 0, 0); }
    }

    /**
     * One event of a person or a family: its tag, date, place or value, and every citation the file gives for it. For an adoption
     * ({@code ADOP}), {@code type} is its descriptor (2 TYPE 婿養子), {@code famc} the family it adopted the person into and {@code by} which
     * of its parents adopted (HUSB, WIFE, BOTH). {@code more}: the tags of the library's own records under it (_HOW, _LEFT, _ROLE, the house).
     */
    private static final class Ev {
        String tag, date = "", place = "", movedFrom = "", written = "", age = "", type = "", famc = "", by = "";
        final List<String> cites = new ArrayList<>();
        final Map<String, String> more = new LinkedHashMap<>();
        Ev(String tag, String place) { this.tag = tag; this.place = place; }
    }

    /**
     * One name of a person as the file gives it: a NAME record, or a married name or an alias written under one. {@code raw}: as the file
     * writes it ("健二 /遠藤/"); {@code first}: the person's first NAME, the name they are filed under. Its written forms are the ROMN, FONE and
     * TRAN under it; its kind comes from its TYPE, or from the library's own {@code _NAMEKIND}; its period from {@code _NAMEDATE} (or a DATE
     * some programs write under a name); {@code event} is the event it dates from ({@code _NAMEEVENT}: ADOP, MARR, _SUCC).
     */
    private static final class GName {
        final String tag, raw;
        final boolean first;
        String written, given, family, type = "", phrase = "", kind = "", nameDate = "", date = "", event = "";
        /** The kind the whole record gives a name the file writes no kind for: "birth" for the first NAME before a name the file says came later. */
        String inferred = "";
        /** A written form of this name is the name of another entry in the library, which the record was not filed under. */
        boolean namesAnother;
        /** The file spells this name another way too, in a NAME it calls a spelling: the spelling is one of its forms. */
        boolean spelt;
        final List<FamilyNameHistory.Form> forms = new ArrayList<>();
        final Ev ev = new Ev("NAME", "");
        GName(String tag, String raw, boolean first, String written, String given, String family) {
            this.tag = tag; this.raw = raw.strip(); this.first = first; this.written = written; this.given = given; this.family = family;
        }
    }

    /** A family the file describes as a family, in the library's own record ({@code 0 @H1@ _HOUSE}): its names, its seat, the family it branched from. */
    private static final class House {
        final String xref;
        final List<String> names = new ArrayList<>();
        String seat = "", branch = "";
        House(String xref) { this.xref = xref; }
    }

    private static final class Indi {
        final String xref;
        String name = "", given = "", sex = "", uid = "";
        final List<Ev> events = new ArrayList<>(), adoptions = new ArrayList<>(), members = new ArrayList<>();
        final List<GName> names = new ArrayList<>();
        final List<String> famc = new ArrayList<>(), fams = new ArrayList<>(), notes = new ArrayList<>(), todos = new ArrayList<>();
        final Set<String> adoptedInto = new LinkedHashSet<>();
        /** For each family the person is a child in, how the file says they belong to it (PEDI: birth, adopted, foster, …). */
        final Map<String, String> pedigree = new LinkedHashMap<>();
        final List<PersonIds.Id> ids = new ArrayList<>();
        final List<String[]> assoc = new ArrayList<>();   // [the other person's xref, the role the file gives them]
        Indi(String xref) { this.xref = xref; }
        Integer year(String... tags) { FamilyDate d = date(tags); return d == null ? null : d.year(); }
        /** The first date among these events the library can read, with its range: "BET 1840 AND 1860" is every year from 1840 to 1860. */
        FamilyDate date(String... tags) {
            for (String t : tags) for (Ev e : events) if (e.tag.equals(t)) { FamilyDate d = FamilyDate.parse(e.date); if (d != null) return d; }
            return null;
        }
        /** The adoption the file records into this family (ADOP with FAMC), or null. */
        Ev adoption(String famXref) {
            for (Ev a : adoptions) if (a.famc.equals(famXref)) return a;
            return null;
        }
    }

    private static final class Fam {
        final String xref; String husb = "", wife = "";
        final List<String> chil = new ArrayList<>();
        /** For each child, how it belongs to the husband and to the wife, as some programs write it under CHIL (_FREL, _MREL). */
        final Map<String, String[]> rel = new LinkedHashMap<>();
        final Ev marr = new Ev("MARR", "");
        Fam(String xref) { this.xref = xref; }
    }

    /** What a file holds, read and not yet written. {@code notRead}: "INDI EDUC" → how many such lines the library does not read. */
    record Parsed(Map<String, Indi> indis, Map<String, Fam> fams, Map<String, String> sourceTitles, String charset, String headDate, List<String> problems, Map<String, Integer> notRead,
                  Map<String, House> houses) { }

    private Gedcom() { }

    /** Events the library files as claims or reads for the living rule. */
    private static final Set<String> EVENTS = Set.of("BIRT", "CHR", "BAPM", "DEAT", "BURI", "CREM", "RESI", "CENS", "IMMI", "EMIG", "OCCU", "PROB", "WILL");
    /** Events a person has once: a later copy of the file that says something else about one of them corrects the earlier copy. */
    private static final Set<String> ONCE = Set.of("BIRT", "CHR", "BAPM", "DEAT", "BURI", "CREM", "MARR");
    /** Lines that carry nothing to file: the file's own bookkeeping. */
    private static final Set<String> BOOKKEEPING = Set.of("CHAN", "RIN", "_UID", "UID", "_FTSID", "_UPD", "_PRIM", "REFN", "SUBM", "_APID", "RESN");
    /** What the tags the library does not read mean, for the list of what was left out. */
    private static final Map<String, String> MEANING = Map.ofEntries(
            Map.entry("EDUC", "schooling"), Map.entry("RELI", "religion"), Map.entry("NATU", "naturalisation"), Map.entry("DSCR", "a description of the person"),
            Map.entry("TITL", "titles"), Map.entry("EVEN", "other events"), Map.entry("FACT", "other facts"), Map.entry("OBJE", "pictures and other media"),
            Map.entry("SOUR", "sources given for the person or the family as a whole"), Map.entry("ASSO", "associates"), Map.entry("GRAD", "graduation"),
            Map.entry("RETI", "retirement"), Map.entry("CONF", "confirmation"), Map.entry("FCOM", "first communion"), Map.entry("ORDN", "ordination"),
            Map.entry("ADDR", "addresses"), Map.entry("DIV", "divorce"), Map.entry("ENGA", "engagement"), Map.entry("MARB", "banns of marriage"),
            Map.entry("MARL", "marriage licence"), Map.entry("MARC", "marriage contract"), Map.entry("ANUL", "annulment"), Map.entry("NOTE", "notes"),
            Map.entry("NCHI", "the number of children"), Map.entry("NMR", "the number of marriages"), Map.entry("CAST", "caste"), Map.entry("NATI", "nationality"),
            Map.entry("SSN", "a social security number"), Map.entry("IDNO", "an identity number"), Map.entry("PROP", "property"), Map.entry("ADOP", "adoption"),
            Map.entry("BARM", "bar mitzvah"), Map.entry("BASM", "bat mitzvah"), Map.entry("BLES", "blessing"), Map.entry("CHRA", "adult christening"));
    /** What a dated event the file gives without a place is filed as: a life event in these words, with the date in brackets. */
    static final Map<String, String> UNPLACED = Map.of("DEAT", "died", "BURI", "buried", "CREM", "cremated", "RESI", "lived at an address the file does not give",
            "CENS", "counted in a census", "IMMI", "immigrated", "EMIG", "emigrated", "OCCU", "at work, at a job the file does not name");

    /** A birth date that is really a baptism's, or a death date that is a burial's: "Bapt 18 Dec 1690". */
    private static final Pattern LABELLED = Pattern.compile("(?i)^(bapt(?:ised|ized|ism)?|bap|chr|christened|bur|buried)\\.?\\s+(.+)$");

    public static Outcome importFile(LibraryStore store, Path file) throws IOException {
        Parsed p = parse(file);
        FamilyPeople.holdsAFamily(store);   // the library holds family work from now on: the family's pages are in the menu
        String fileName = file.getFileName().toString();
        String source = "file://" + file.toAbsolutePath().normalize();
        Writer w = new Writer(store, source, p.headDate());
        Graph held = FamilyPeople.view(store);
        List<String> apart = new ArrayList<>();
        Map<String, String> label = labels(store, p, fileName, source, held, w.all, apart);
        saveLabels(store, p, fileName, label);
        // a first name written in a form that is the name of another entry, which the record was not filed under: the name is filed with its
        // forms, so the file's word that they are one name stays, and the question whether the two entries are one person can be asked
        Map<String, String> knownHeld = FamilyNames.known(held);
        for (Indi i : p.indis().values()) {
            String who = label.get(i.xref);
            if (who == null || i.names.isEmpty() || !i.names.get(0).first) continue;
            String me = held.nodeIdOf(who);
            for (FamilyNameHistory.Form f : i.names.get(0).forms) {
                Graph.Node n = held.node(held.nodeIdOf(FamilyNames.resolve(held, knownHeld, f.text())));
                if (n != null && "person".equals(n.kind()) && !n.id().equals(me) && !FamilyQuestions.placeholder(n.label())) { i.names.get(0).namesAnother = true; break; }
            }
        }
        List<String> problems = new ArrayList<>(p.problems());
        Map<String, Indi> indis = p.indis();

        // nodes: every person, of kind person
        Map<String, Integer> nameCount = new HashMap<>();
        List<String[]> todos = new ArrayList<>();   // [person, the note]
        for (Indi i : indis.values()) for (String n : otherNamesOf(i)) nameCount.merge(Vocabulary.norm(n), 1, Integer::sum);
        for (Indi i : indis.values()) nameCount.merge(Vocabulary.norm(i.name), 1, Integer::sum);
        for (Indi i : indis.values()) {
            if (i.name.isBlank()) { problems.add(i.xref + " has no name, so the library skipped it."); continue; }
            String who = label.get(i.xref);
            Graph.setKind(store, who, "person");
            // other names: married names, readings, romanised forms; a name that another person in the file or the library also has would join them
            List<String> others = new ArrayList<>();
            List<String> names = new ArrayList<>(otherNamesOf(i));
            if (!Vocabulary.norm(who).equals(Vocabulary.norm(i.name)) && !bare(who).equals(i.name)) names.add(0, i.name);   // filed under another of its names: the first one is another name of theirs
            for (String o : names) {
                if (!FamilyNames.keepAsOtherName(who, o) || nameCount.getOrDefault(Vocabulary.norm(o), 0) > 1 || others.contains(o)) continue;
                Graph.Node there = held.node(held.nodeIdOf(o));
                if (there != null && !there.id().equals(held.nodeIdOf(who))) continue;
                others.add(o);
            }
            if (!others.isEmpty()) Graph.alias(store, who, others, source);
            for (PersonIds.Id id : i.ids) PersonIds.add(store, who, id);
            String sex = FamilyKin.sexWord(i.sex);
            if (!sex.isEmpty()) { Graph.setKind(store, sex, "value"); w.edge(who, "sex", sex, "", List.of("GEDCOM " + i.xref + " SEX " + i.sex.strip()), "", i.xref + " SEX"); }
            String notes = i.notes.isEmpty() ? "" : String.join(" / ", i.notes);
            for (Ev e : i.events) {
                String key = ONCE.contains(e.tag) ? i.xref + " " + e.tag : null;
                List<String> editions = editions(i.xref + " " + e.tag, e, p.sourceTitles());
                String date = e.date;
                // an age at a dated event is a birth year the record gives: kept as its own claim, as written
                if (!e.age.isBlank() && !date.isBlank() && FamilyDate.age(e.age) != null) w.edge(who, "aged", e.age, date, editions(i.xref + " AGE at " + e.tag, e, p.sourceTitles()), "", null);
                switch (e.tag) {
                    case "BIRT", "DEAT", "CHR", "BAPM" -> {
                        String stem = e.tag.equals("BIRT") ? "born" : e.tag.equals("DEAT") ? "died" : "baptised";
                        // a date the file gives without a place is kept as a date, and a death with neither as that the person died
                        if (!e.place.isBlank()) w.edge(who, stem + "-in", place(store, e.place), date, editions, notes, key);
                        else if (!date.isBlank()) w.edge(who, stem + "-on", date, "", editions, notes, key);
                        else if (e.tag.equals("DEAT")) w.edge(who, "life-event", UNPLACED.get("DEAT"), "", editions, notes, key);
                    }
                    // an event the file gives without a place is filed as what happened, with its date when it has one: a burial the file
                    // gives with neither a date nor a place still says the person has died
                    case "BURI" -> {
                        if (!e.place.isBlank()) w.edge(who, "buried-in", place(store, e.place), date, editions, notes, key);
                        else w.edge(who, "life-event", UNPLACED.get("BURI"), date, editions, notes, key);
                    }
                    case "RESI", "CENS" -> {
                        if (!e.place.isBlank()) w.edge(who, "lived-in", place(store, e.place), date, editions, notes, null);
                        else if (!date.isBlank()) w.edge(who, "life-event", UNPLACED.get(e.tag), date, editions, notes, null);
                    }
                    case "IMMI", "EMIG" -> {
                        if (!e.place.isBlank()) w.edge(who, "migrated-to", place(store, e.place), date, editions, notes, null);
                        else if (!date.isBlank()) w.edge(who, "life-event", UNPLACED.get(e.tag), date, editions, notes, null);
                    }
                    case "OCCU" -> {
                        if (!e.place.isBlank()) w.edge(who, "occupation", e.place, date, editions, notes, null);
                        else if (!date.isBlank()) w.edge(who, "life-event", UNPLACED.get("OCCU"), date, editions, notes, null);
                    }
                    case "CREM" -> {
                        if (!e.place.isBlank()) w.edge(who, "life-event", "cremated in " + e.place, date, editions, notes, key);
                        else w.edge(who, "life-event", UNPLACED.get("CREM"), date, editions, notes, key);
                    }
                    case "PROB", "WILL" -> {
                        String what = e.tag.equals("PROB") ? "will proved" : "made a will";
                        if (!e.place.isBlank() || !date.isBlank()) w.edge(who, "life-event", what + (e.place.isBlank() ? "" : " in " + e.place), date, editions, notes, null);
                    }
                    default -> { }
                }
            }
            for (String t : i.todos) todos.add(new String[]{who, t});
        }
        // a godparent, a witness or an informant the file names for a person: a lead, never a relative
        for (Indi i : indis.values()) {
            String who = label.get(i.xref);
            if (i.name.isBlank() || who == null) continue;
            for (String[] a : i.assoc) {
                String other = label.getOrDefault(a[0], ""), rel = associate(a[1]);
                if (!other.isEmpty() && rel != null) w.edge(other, rel, who, "", List.of("GEDCOM " + i.xref + " ASSO " + a[0] + " " + a[1]), "", null);
            }
        }
        // the claims of the events a name can come from, by the person's record: {claim, date, the other person's record, the family's record}
        Map<String, List<String[]>> marriages = new HashMap<>(), adoptions = new HashMap<>();
        for (Fam f : p.fams().values()) {
            String h = label.getOrDefault(f.husb, ""), wi = label.getOrDefault(f.wife, "");
            if (!h.isEmpty() && !wi.isEmpty()) {
                String id = w.edge(h, "married-to", wi, f.marr.date, editions(f.xref + " MARR" + (f.marr.place.isBlank() ? "" : " at " + f.marr.place), f.marr, p.sourceTitles()), "", f.xref + " MARR");
                if (id != null) {
                    marriages.computeIfAbsent(f.husb, k -> new ArrayList<>()).add(new String[]{id, f.marr.date, f.wife, f.xref});
                    marriages.computeIfAbsent(f.wife, k -> new ArrayList<>()).add(new String[]{id, f.marr.date, f.husb, f.xref});
                }
            }
            for (String c : f.chil) {
                String child = label.getOrDefault(c, "");
                if (child.isEmpty()) continue;
                // how the child belongs to the family: the child's own PEDI for it, else what the family writes under CHIL for each parent,
                // and the parent an adoption the file records names as the one who adopted
                Indi ci = indis.get(c);
                String[] kinds = belongs(ci, f, c);
                Ev adop = ci == null ? null : ci.adoption(f.xref);
                String[] parents = {f.husb, f.wife}, names = {h, wi};
                for (int k = 0; k < 2; k++) {
                    if (names[k].isEmpty()) continue;
                    if (NOT_SAID.equals(kinds[k])) {
                        problems.add("The file says that " + child + " was adopted by " + names[1 - k] + " alone, and it does not say how " + child + " is related to " + names[k]
                                + ", the other parent of that family. The library filed no link between " + child + " and " + names[k] + ".");
                        continue;
                    }
                    String id = parentEdge(w, names[k], child, kinds[k], f.xref + " CHIL", adop, p.sourceTitles());
                    if (id != null && kinds[k].strip().toLowerCase(Locale.ROOT).startsWith("adopt"))
                        adoptions.computeIfAbsent(c, x -> new ArrayList<>()).add(new String[]{id, adop == null ? "" : adop.date, parents[k], f.xref});
                }
            }
        }
        // the families the file describes as families, in the library's own _HOUSE records: never made from a surname
        Map<String, String> houseLabel = houses(store, p, fileName, label, problems);
        for (House h : p.houses().values()) {
            String l = houseLabel.get(h.xref);
            if (l == null) continue;
            if (!h.seat.isBlank()) w.edge(l, FamilyHouses.SEAT, place(store, h.seat), "", List.of("GEDCOM " + h.xref + " _SEAT"), "", h.xref + " _SEAT");
            String of = houseLabel.get(h.branch);
            if (of != null) w.edge(l, FamilyHouses.BRANCH, of, "", List.of("GEDCOM " + h.xref + " _BRANCH " + h.branch), "", h.xref + " _BRANCH");
        }
        // who belonged to them, with how they came and left, the years and their role
        int members = 0;
        Map<String, List<String[]>> heads = new HashMap<>();
        Set<String> missing = new LinkedHashSet<>();
        for (Indi i : indis.values()) {
            String who = label.get(i.xref);
            if (i.name.isBlank() || who == null) continue;
            List<Ev> ms = new ArrayList<>(i.members);
            mukoyoshi(i, indis, adoptions.getOrDefault(i.xref, List.of()), ms);
            for (Ev m : ms) {
                String hx = m.more.getOrDefault("house", ""), family = houseLabel.get(hx);
                if (family == null) { if (!hx.isBlank() && !p.houses().containsKey(hx)) missing.add(hx); continue; }
                String[] period = period(m.date);
                String role = word(m.more.getOrDefault("_ROLE", ""));
                Map<String, String> d = FamilyHouses.detail(word(m.more.getOrDefault("_HOW", "")), word(m.more.getOrDefault("_LEFT", "")), period[0], period[1], role, m.more.getOrDefault("event", ""), "");
                String what = i.xref + " _MEMBER " + hx + (m.more.containsKey("derived") ? ", " + m.more.get("derived") : "");
                String id = w.edge(who, FamilyHouses.MEMBER, family, period[0], editions(what, m, p.sourceTitles()), "", i.xref + " _MEMBER", Finding.Confidence.medium, d);
                if (id == null) continue;
                members++;
                if (role.equals("head") || role.equals("heir")) heads.computeIfAbsent(i.xref, k -> new ArrayList<>()).add(new String[]{id, period[0]});
            }
        }
        if (!missing.isEmpty()) problems.add("The file names " + (missing.size() == 1 ? "a family, " + missing.iterator().next() + ", that it does not describe" : missing.size() + " families that it does not describe (" + String.join(", ", missing) + ")")
                + ", so the memberships of " + (missing.size() == 1 ? "that family" : "those families") + " were not filed.");
        // every name of each person, with its kind, its years, the event it came from and its written forms
        int names = 0;
        List<String> values = new ArrayList<>();
        for (Indi i : indis.values()) for (GName n : i.names) if (claimed(n, i) && label.get(i.xref) != null) values.add(FamilyNameHistory.VALUE + n.written);
        if (!values.isEmpty()) FamilyNameHistory.markValue(store, values);
        for (Indi i : indis.values()) {
            String who = label.get(i.xref);
            if (i.name.isBlank() || who == null) continue;
            for (GName n : i.names) {
                if (!claimed(n, i) || n.written.isBlank()) continue;
                String kind = nameKind(n);
                String[] period = period(n.nameDate.isBlank() ? n.date : n.nameDate);
                String[] event = nameEvent(n, kind, period[0], adoptions.getOrDefault(i.xref, List.of()), marriages.getOrDefault(i.xref, List.of()), heads.getOrDefault(i.xref, List.of()));
                // a name the file dates by its event, and gives no date of its own: its years start with the event's
                boolean fromEvent = period[0].isBlank() && !n.event.isBlank() && event != null;
                List<FamilyNameHistory.Form> forms = new ArrayList<>(List.of(new FamilyNameHistory.Form(n.written, FamilyForms.lang(n.written))));
                for (FamilyNameHistory.Form f : n.forms) if (forms.stream().noneMatch(x -> x.text().equals(f.text()))) forms.add(f);
                Map<String, String> d = FamilyNameHistory.detail(n.family, n.given, kind, fromEvent ? "event" : period[0], period[1], event == null ? "" : event[0], forms, said(n), FamilyForms.lang(n.written));
                String what = i.xref + " NAME " + (n.tag.equals("NAME") ? "" : n.tag + " ") + n.raw
                        + (n.inferred.equals("birth") ? ", read as the name at birth: the name before the other name the file gives, which it says came later" : "");
                String id = w.edge(who, FamilyNameHistory.PREDICATE, FamilyNameHistory.VALUE + n.written, fromEvent ? event[1] : period[0], editions(what, n.ev, p.sourceTitles()), "", i.xref + " NAME", Finding.Confidence.medium, d);
                if (id != null) names++;
            }
        }
        w.finish();
        if (w.renamed > 0) problems.add(w.renamed + (w.renamed == 1 ? " name, membership or family's seat from an earlier copy of this file was" : " names, memberships or families' seats from an earlier copy of this file were")
                + " replaced, because this copy of the file says something else about " + (w.renamed == 1 ? "it" : "them") + ". The earlier " + (w.renamed == 1 ? "one is" : "ones are") + " kept, marked as replaced.");
        // whom nothing places in the past once the file is in: the graph works it out from every claim, the file's among them
        Graph now = FamilyPeople.view(store);
        int mayBeLiving = 0;
        Set<String> filePeople = new HashSet<>();
        for (Indi i : indis.values()) {
            String who = label.get(i.xref);
            if (i.name.isBlank() || who == null || !filePeople.add(now.nodeIdOf(who))) continue;
            Graph.Node n = now.node(now.nodeIdOf(who));
            if (n != null && n.mayBeLiving()) mayBeLiving++;
        }
        // the research notes last: each becomes an open question
        int notes = 0;
        for (String[] t : todos) if (todo(store, t[0], t[1])) notes++;
        if (notes > 0) problems.add(notes + (notes == 1 ? " research note in " + fileName + " was" : " research notes in " + fileName + " were") + " added to your open questions.");
        for (Map.Entry<String, Integer> n : p.notRead().entrySet()) problems.add(notRead(n.getKey(), n.getValue()));
        if (w.backed > 0) problems.add(w.backed + (w.backed == 1 ? " fact in the file was" : " facts in the file were") + " already in your library from another source. The file was added to " + (w.backed == 1 ? "that fact" : "each of them") + " as a further source, so the fact now shows every source that gives it.");
        store.circulate("gedcom-import", indis.size() + " persons, " + p.fams().size() + " families, " + w.written + " draft findings from " + fileName);
        return new Outcome(indis.size(), p.fams().size(), w.written, mayBeLiving, problems, w.superseded, apart, w.moved, names, houseLabel.size(), members);
    }

    // ---- names and families ----

    /**
     * Whether a name is filed as a name claim: every name after the first, a married name and an alias, and the first name when the file
     * says more of it than its words: a date, the event it came from, or a kind, unless it is the person's only name and they were born with
     * it; a spelling of it; or a written form of it that is the name of another entry. The first name with nothing more is the name the
     * person is filed under, and its forms are the person's other names as before.
     */
    private static boolean claimed(GName n, Indi i) {
        if (!n.first || !n.nameDate.isBlank() || !n.date.isBlank() || !n.event.isBlank() || n.namesAnother || n.spelt) return true;
        if (n.type.isBlank() && n.kind.isBlank() && n.inferred.isBlank()) return false;
        return i.names.size() > 1 || !nameKind(n).equals("birth");
    }

    /** The words GEDCOM's NAME TYPE uses (5.5.1 and 7), for which the file's word is no word of its own. */
    private static final Set<String> NAME_TYPES = Set.of("birth", "maiden", "married", "aka", "immigrant", "professional", "other");

    /**
     * The kind of a name: the library's own {@code _NAMEKIND} first; a married name ({@code _MARNM}) is taken at marriage and an alias
     * ({@code _AKA}) is also known as; else the NAME TYPE (birth and maiden at birth, married, aka, immigrant, professional an art name), and
     * any other word by what it says (婿養子, pen name), with OTHER's PHRASE. "unknown" when the file does not say.
     */
    static String nameKind(GName n) {
        String k;
        if (!n.kind.isBlank()) k = FamilyNameHistory.kind(n.kind);
        else if (!n.inferred.isBlank()) k = n.inferred;
        else if (n.tag.equals("_MARNM")) k = "marriage";
        else if (n.tag.equals("_AKA")) k = "aka";
        else {
            String t = n.type.strip().toLowerCase(Locale.ROOT);
            k = switch (t) {
                case "birth", "maiden" -> "birth";
                case "married" -> "marriage";
                case "aka", "also known as" -> "aka";
                case "immigrant" -> "immigrant";
                case "professional" -> "art";
                case "", "other" -> kindInWords(n.phrase);
                default -> { String x = FamilyNameHistory.kind(t); yield x.equals("unknown") ? kindInWords(n.type) : x; }
            };
        }
        // a hereditary head name is the family's; the person who carries it took it on succeeding
        return k.equals("hereditary") ? "succession" : k;
    }

    /**
     * The words family-tree programs offer as a NAME TYPE for a name somebody was called by beside their own (Nickname, Call name, 愛称):
     * a name they were also known by. Defaults behind the rule that the file's words decide the kind.
     */
    private static final Pattern CALLED = Pattern.compile("(?i)\\bnick\\s*-?\\s*name\\b|\\bcall(?:ed|ing)?\\s*-?\\s*name\\b|\\brufname\\b|愛称|あだ名|呼び名");

    /**
     * The words of a NAME TYPE that say the name is the same name spelt another way (Other Spelling, Variant spelling, 別表記): a written form
     * of the name it spells, not a name of its own.
     */
    private static final Pattern SPELLING = Pattern.compile("(?i)\\bspell(?:ing|ed|t)?s?\\b|\\bmisspel\\w*|綴り|別表記|異表記");

    /** The kind the words say ({@link FamilyNameHistory#saysKind}), the first that fits; "unknown" when none does. */
    private static String kindInWords(String words) {
        if (words == null || words.isBlank()) return "unknown";
        for (String k : FamilyNameHistory.SAYS.keySet()) if (FamilyNameHistory.saysKind(words, k)) return k;
        if (CALLED.matcher(words).find()) return "aka";
        return "unknown";
    }

    /** The file's own words for how a name came, when they are its own and not one of GEDCOM's: 婿養子, "took the name of the farm". */
    private static String said(GName n) {
        String t = n.type.strip();
        if (!t.isEmpty() && !NAME_TYPES.contains(t.toLowerCase(Locale.ROOT)) && !ownWord(t)) return t;
        return n.phrase.strip();
    }

    /** Whether a NAME TYPE is a word the library itself writes for a kind (mukoyoshi, succession, or a kind in words): its own export read back. */
    private static boolean ownWord(String type) {
        String t = type.strip();
        return FamilyNameHistory.KINDS.containsKey(t.toLowerCase(Locale.ROOT)) || FamilyNameHistory.KINDS.containsValue(t);
    }

    /**
     * The claim of the event a name came from, {claim, its date}: the event the file names ({@code _NAMEEVENT}: ADOP, MARR, _SUCC), else the
     * one the name's kind implies (an adoption for a name taken on adoption or as 婿養子, a marriage for a married name or 入夫, becoming head
     * for a succession). One of the name's own year is taken; with no year, the only one there is. Null when none fits.
     */
    private static String[] nameEvent(GName n, String kind, String from, List<String[]> adoptions, List<String[]> marriages, List<String[]> heads) {
        String tag = n.event.strip().toUpperCase(Locale.ROOT);
        List<String[]> fit = switch (tag) {
            case "ADOP" -> adoptions;
            case "MARR" -> marriages;
            case "_SUCC", "SUCC", "_MEMBER" -> heads;
            default -> switch (kind) {
                case "mukoyoshi", "adoptive" -> adoptions;
                case "marriage", "nyufu" -> marriages;
                case "succession" -> heads;
                default -> List.of();
            };
        };
        if (fit.isEmpty()) return null;
        FamilyDate year = FamilyDate.parse(from);
        if (year != null) for (String[] f : fit) { FamilyDate d = FamilyDate.parse(f[1]); if (d != null && d.year() == year.year()) return f; }
        // the file named the event: the first of its kind; the kind alone links an undated name to the only such event. One event can give
        // several claims: an adoption by both parents of a family is one adoption, filed as one claim for each parent
        if (!tag.isEmpty()) return fit.get(0);
        Set<String> events = new LinkedHashSet<>();
        for (String[] f : fit) events.add(f.length > 3 ? f[3] + "\t" + f[1] : f[0]);
        return year == null && events.size() == 1 ? fit.get(0) : null;
    }

    /** A period as the file writes it ("FROM 1905 TO 1932", "FROM 1932", "TO 1932", "1932"): {from, to}, each as written, "" where it gives none. */
    static String[] period(String written) {
        String w = written == null ? "" : written.strip();
        Matcher m = Pattern.compile("(?i)^(?:FROM\\s+(.+?))?\\s*(?:\\bTO\\s+(.+))?$").matcher(w);
        if (!w.isEmpty() && m.matches() && (m.group(1) != null || m.group(2) != null))
            return new String[]{m.group(1) == null ? "" : m.group(1).strip(), m.group(2) == null ? "" : m.group(2).strip()};
        return new String[]{w, ""};
    }

    /** A membership's word as the library keeps it: 婿養子 is mukoyoshi and 入夫 nyufu; any other in lower case, words joined by a hyphen (adoption-out). */
    private static String word(String v) {
        String w = v == null ? "" : v.strip();
        if (w.contains("婿養子")) return "mukoyoshi";
        if (w.contains("入夫")) return "nyufu";
        return w.toLowerCase(Locale.ROOT).replaceAll("[\\s_]+", "-");
    }

    /**
     * 婿養子 is an adoption, a marriage and an entry into the wife's family at once. An adoption the file calls 婿養子 makes the person's
     * membership one by 婿養子: the membership of the family the adopting parent belongs to, or else the one that begins in the adoption's year,
     * when the file gives it no way in of its own. When the file gives the person no membership of the adopting parent's one family, that
     * membership is filed from the adoption. The adoption's claim is the membership's event.
     */
    private static void mukoyoshi(Indi i, Map<String, Indi> indis, List<String[]> adopted, List<Ev> ms) {
        for (Ev a : i.adoptions) {
            if (!"mukoyoshi".equals(adoptionDetail(a).get("kind"))) continue;
            String claim = "";
            Set<String> houses = new LinkedHashSet<>();
            for (String[] x : adopted) {
                if (!x[3].equals(a.famc)) continue;
                if (claim.isEmpty()) claim = x[0];
                Indi parent = indis.get(x[2]);
                if (parent != null) for (Ev pm : parent.members) houses.add(pm.more.getOrDefault("house", ""));
            }
            houses.remove("");
            FamilyDate when = FamilyDate.parse(a.date);
            Ev into = null;
            // a membership that gives no way in, or gives 婿養子 itself (as the library's own export writes it), is the one the adoption entered
            for (Ev m : ms) if (byMukoyoshi(m) && houses.contains(m.more.get("house"))) { into = m; break; }
            if (into == null && when != null) for (Ev m : ms) {
                FamilyDate from = FamilyDate.parse(period(m.date)[0]);
                if (byMukoyoshi(m) && from != null && from.year() == when.year()) { into = m; break; }
            }
            if (into == null && houses.size() == 1 && ms.stream().noneMatch(m -> houses.contains(m.more.get("house")))) {
                into = new Ev("_MEMBER", "");
                into.more.put("house", houses.iterator().next());
                into.more.put("derived", "entered by the 婿養子 adoption the file records");
                into.date = a.date.isBlank() ? "" : "FROM " + a.date;
                into.cites.addAll(a.cites);
                ms.add(into);
            }
            if (into == null) continue;
            into.more.put("_HOW", "mukoyoshi");
            if (!claim.isEmpty()) into.more.putIfAbsent("event", claim);
        }
    }

    /** Whether a membership gives no way in, or says the way in was 婿養子. */
    private static boolean byMukoyoshi(Ev m) {
        String how = m.more.getOrDefault("_HOW", "");
        return how.isBlank() || word(how).equals("mukoyoshi");
    }

    /**
     * The families the file describes, found or made in the library ({@link FamilyHouses#family}): the file's record → the family's label. A
     * family found for a record of this file before is found again, so a second import lands on the same families. A family's name with a
     * family word (森田家, the Morita family) is kept as its other name.
     */
    private static Map<String, String> houses(LibraryStore store, Parsed p, String fileName, Map<String, String> label, List<String> problems) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (p.houses().isEmpty()) return out;
        Map<String, String> saved = new HashMap<>();
        if (Files.exists(labelsFile(store))) for (String line : Files.readAllLines(labelsFile(store), StandardCharsets.UTF_8)) {
            String[] x = line.split("\t");
            if (x.length >= 4 && x[0].equals(fileName.replace('\t', ' ')) && x[1].startsWith("@H")) saved.put(x[1], x[3]);
        }
        Graph g = FamilyPeople.view(store);
        List<String> made = new ArrayList<>();
        for (House h : p.houses().values()) {
            if (h.names.isEmpty()) { problems.add("The family " + h.xref + " in the file has no name, so the library did not read it."); continue; }
            String was = saved.get(h.xref);
            if (was != null && FamilyHouses.isFamily(g, g.nodeIdOf(was))) { out.put(h.xref, FamilyHouses.labelOf(g, g.nodeIdOf(was))); continue; }
            String name = FamilyHouses.familyName(h.names.get(0));
            // a family of the same name made a moment ago is in the library now: this one is told apart from it
            if (made.stream().anyMatch(m -> FamilyForms.sameForm(m, name))) g = FamilyPeople.view(store);
            String first = "";
            for (Indi i : p.indis().values()) if (label.get(i.xref) != null && i.members.stream().anyMatch(m -> h.xref.equals(m.more.get("house")))) { first = label.get(i.xref); break; }
            List<String> written = h.names.stream().filter(n -> !FamilyHouses.familyName(n).equals(n.strip())).toList();
            // several families of the name, which the name and the seat cannot choose from: the one the record's members already belong to,
            // as they do when the library's own export is read back into it
            String theirs = FamilyHouses.find(g, name, h.seat) == null ? heldBy(g, p, h, name, label) : null;
            if (theirs != null && !out.containsValue(FamilyHouses.labelOf(g, theirs))) { out.put(h.xref, FamilyHouses.labelOf(g, theirs)); made.add(name); continue; }
            // two records of the file are two families by the file's own word, even where a family of the name without a seat could be either
            String found = FamilyHouses.find(g, name, h.seat);
            String l = found != null && out.containsValue(FamilyHouses.labelOf(g, found)) ? apart(store, g, name, h.seat, first, written)
                    : FamilyHouses.family(store, g, h.names.get(0), h.seat, first, written);
            if (l == null) continue;
            out.put(h.xref, l);
            made.add(name);
        }
        Path f = labelsFile(store);
        Set<String> have = new HashSet<>(Files.exists(f) ? Files.readAllLines(f, StandardCharsets.UTF_8) : List.of());
        StringBuilder add = new StringBuilder();
        for (Map.Entry<String, String> e : out.entrySet()) {
            String line = fileName.replace('\t', ' ') + "\t" + e.getKey() + "\t\t" + e.getValue();
            if (have.add(line)) add.append(line).append('\n');
        }
        if (!add.isEmpty()) {
            Files.createDirectories(f.getParent());
            Files.writeString(f, add.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        return out;
    }

    /**
     * The one family of this name, among the several its name and seat cannot choose from, that the people the file names as the record's
     * members already belong to in the library, and whose seat is the record's when both give one. Null when none does, or more than one.
     */
    private static String heldBy(Graph g, Parsed p, House h, String name, Map<String, String> label) {
        List<String> same = FamilyHouses.named(g, name);
        if (same.size() < 2) return null;
        Set<String> hold = new LinkedHashSet<>();
        for (Indi i : p.indis().values()) {
            String who = label.get(i.xref);
            if (who == null || i.members.stream().noneMatch(m -> h.xref.equals(m.more.get("house")))) continue;
            for (FamilyHouses.Membership m : FamilyHouses.families(g, g.nodeIdOf(who))) if (same.contains(m.family())) hold.add(m.family());
        }
        if (hold.size() != 1) return null;
        String f = hold.iterator().next(), seat = FamilyHouses.seat(g, f);
        return h.seat.isBlank() || seat.isBlank() || Vocabulary.norm(KanjiForms.modern(seat)).equals(Vocabulary.norm(KanjiForms.modern(h.seat))) || Graph.samePlace(seat, h.seat) ? f : null;
    }

    /**
     * A family of its own for a record of the file whose name and seat would find the family another record of the same file is: labelled with
     * its seat, or with its first member, as {@link FamilyHouses#family} labels a second family of a name. Its written forms are its other
     * names where they lead to no other entry.
     */
    private static String apart(LibraryStore store, Graph g, String name, String seat, String first, List<String> written) throws IOException {
        Vocabulary nodes = Vocabulary.read(Graph.nodesFile(store));
        String base = FamilyHouses.label(name, seat, first), label = base;
        for (int i = 2; (nodes.get(Vocabulary.norm(label)) != null || nodes.resolve(label) != null || g.node(Vocabulary.norm(label)) != null) && i < 100; i++) label = base + " (" + i + ")";
        List<String> also = new ArrayList<>();
        for (String w : written) if (nodes.resolve(w) == null && g.node(Vocabulary.norm(w)) == null && !Vocabulary.norm(w).equals(Vocabulary.norm(label))) also.add(w.strip());
        nodes.put(new Vocabulary.Term(Vocabulary.norm(label), FamilyHouses.KIND + ": " + label, also, ""));
        Graph.writeNodes(store, nodes);
        return label;
    }

    /** The relation for the role an ASSO line gives (RELA in GEDCOM 5, ROLE in 7): a godparent, a witness or an informant; null for any other. */
    static String associate(String role) {
        String r = role == null ? "" : role.toLowerCase(Locale.ROOT);
        if (r.matches(".*(godf|godm|godp|sponsor|代父|代母|名付け親).*")) return "godparent-of";
        if (r.matches(".*(witn|証人).*")) return "witness-for";
        if (r.matches(".*(inform|届出).*")) return "informant-for";
        return null;
    }

    private static String place(LibraryStore store, String place) throws IOException { Graph.setKind(store, place, "place"); return place; }

    /** How a child belongs to a parent when the file says the child came into the family by adoption and that this parent did not adopt. */
    static final String NOT_SAID = "not said";

    /**
     * How a child belongs to the husband and to the wife of a family: the family's _FREL and _MREL for each, else the child's own PEDI for the
     * family. An adoption the file records ({@code ADOP} with {@code FAMC} and {@code ADOP HUSB}, {@code WIFE} or {@code BOTH}) types each
     * parent: the one who adopted is an adoptive parent, and the other keeps what the file writes for them, by birth when it writes nothing,
     * and {@link #NOT_SAID} when all it says is that the child came into the family by adoption.
     */
    private static String[] belongs(Indi ci, Fam f, String child) {
        String own = ci == null ? "" : ci.pedigree.getOrDefault(f.xref, "");
        String pedi = ci != null && ci.adoptedInto.contains(f.xref) && own.isEmpty() ? "adopted" : own;
        String[] rel = f.rel.getOrDefault(child, new String[]{"", ""});
        String[] out = {rel[0].isBlank() ? pedi : rel[0], rel[1].isBlank() ? pedi : rel[1]};
        Ev adop = ci == null ? null : ci.adoption(f.xref);
        if (adop == null || !Set.of("HUSB", "WIFE", "BOTH").contains(adop.by)) return out;
        boolean[] adopted = {!adop.by.equals("WIFE"), !adop.by.equals("HUSB")};
        for (int k = 0; k < 2; k++) {
            if (adopted[k]) out[k] = "adopted";
            else if (rel[k].isBlank()) out[k] = own.isBlank() || birthKind(own) ? "" : own.toLowerCase(Locale.ROOT).startsWith("adopt") ? NOT_SAID : own;
        }
        return out;
    }

    /** A kind of belonging that makes the parent a birth parent: none written, birth, natural, biological or unknown. */
    private static boolean birthKind(String kind) { String k = kind == null ? "" : kind.strip().toLowerCase(Locale.ROOT); return k.isEmpty() || k.matches("birth|natural|biological|unknown"); }

    /**
     * One parent and child, filed as the file says the child belongs: born to them (also when the file says unknown), adopted, fostered,
     * or a step-child. Only a birth parent counts for the checks' two parents. A kind the library does not know is kept as a parent at
     * low confidence, and the claim says the word the file used.
     */
    private static String parentEdge(Writer w, String parent, String child, String kind, String why, Ev adop, Map<String, String> sourceTitles) throws IOException {
        String k = kind == null ? "" : kind.strip().toLowerCase(Locale.ROOT);
        if (birthKind(kind)) return w.edge(parent, "parent-of", child, "", List.of("GEDCOM " + why), "", null);
        // an adoption the file records as an event: its date, what kind it was (婿養子, as heir) and its sources are the claim's
        if (k.startsWith("adopt") && adop != null) return w.edge(child, "adopted-by", parent, adop.date, editions(why + ", adopted", adop, sourceTitles), "", null, Finding.Confidence.medium, adoptionDetail(adop));
        if (k.startsWith("adopt")) return w.edge(child, "adopted-by", parent, "", List.of("GEDCOM " + why + ", adopted"), "", null);
        if (k.startsWith("foster")) return w.edge(child, "foster-child-of", parent, "", List.of("GEDCOM " + why + ", foster"), "", null);
        if (k.startsWith("step")) return w.edge(parent, "step-parent-of", child, "", List.of("GEDCOM " + why + ", step"), "", null);
        return w.edge(parent, "parent-of", child, "", List.of("GEDCOM " + why + ", written as " + kind.strip()), "", null, Finding.Confidence.low);
    }

    /** Words in an adoption's descriptor that say it was an heir's adoption: 家督, heir, 出嗣, 入嗣, 継嗣. Defaults behind the rule that the file's words decide the kind. */
    private static final Pattern HEIR = Pattern.compile("(?i)家督|\\bheir\\b|出嗣|入嗣|継嗣|跡継|跡取");

    /**
     * An adoption's reading from the file's ADOP event: its kind as the descriptor says it (婿養子 is mukoyoshi, an heir's adoption is heir, any
     * other word ordinary), the date it began, and the file's own words. Empty when the event gives neither a date nor a descriptor.
     */
    static Map<String, String> adoptionDetail(Ev adop) {
        Map<String, String> d = new LinkedHashMap<>();
        String type = adop.type.strip();
        String kind = type.isEmpty() ? "" : FamilyNameHistory.saysKind(type, "mukoyoshi") ? "mukoyoshi" : HEIR.matcher(type).find() ? "heir" : "ordinary";
        if (!kind.isEmpty()) d.put("kind", kind);
        if (!adop.date.isBlank()) d.put("from", adop.date.strip());
        if (!type.isEmpty() && !type.equalsIgnoreCase(kind)) d.put("said", type);
        return d;
    }

    /** "3 lines of the kind the file calls EDUC (schooling) were not imported…": one sentence per kind of line. */
    private static String notRead(String key, int n) {
        String[] k = key.split(" ", 2);
        String tag = k.length > 1 ? k[1] : key;
        String meaning = MEANING.get(tag.replaceFirst("^_", ""));
        return n + (n == 1 ? " entry" : " entries") + " of the kind the file calls " + tag + (meaning == null ? "" : " (" + meaning + ")") + (k[0].equals("FAM") ? " in its families" : "")
                + (n == 1 ? " was" : " were") + " not imported, because the library does not read that kind of entry yet.";
    }

    /** A research note the file keeps on a person (_TODO) becomes an open question, once: the nightly research takes open questions to search services. */
    private static boolean todo(LibraryStore store, String who, String text) throws IOException {
        String q = who + ": " + text.strip();
        for (Frontier.Line l : Frontier.read(store)) if (l.text().strip().equals(q)) return false;
        store.frontier("person (from a family file)" + Fields.mark("genealogy"), q);
        return true;
    }

    /** Every name of a person but the one they are filed under, and every written form of each: the other names the person is found by. */
    private static List<String> otherNamesOf(Indi i) {
        List<String> out = new ArrayList<>();
        for (GName n : i.names) {
            List<String> all = new ArrayList<>();
            if (!n.first) all.add(n.written);
            for (FamilyNameHistory.Form f : n.forms) all.add(f.text());
            for (String o : all) if (o != null && !o.isBlank() && !Vocabulary.norm(o).equals(Vocabulary.norm(i.name)) && !out.contains(o)) out.add(o);
        }
        return out;
    }

    /** The source line of each citation the file gives for an event: "GEDCOM @I1@ BIRT; cited there: <title>, <page>". One with none when it gives none. */
    private static List<String> editions(String what, Ev e, Map<String, String> sourceTitles) {
        String moved = e.movedFrom.isEmpty() ? "" : "; the file has it under " + e.movedFrom + " as \"" + e.written + "\"";
        if (e.cites.isEmpty()) return List.of("GEDCOM " + what + moved);
        List<String> out = new ArrayList<>();
        for (String c : e.cites) out.add("GEDCOM " + what + moved + citation(c, sourceTitles));
        return out;
    }

    // ---- reading the file ----

    static Parsed parse(Path file) throws IOException {
        List<String> problems = new ArrayList<>();
        String[] charset = {""};
        String text = decode(Files.readAllBytes(file), problems, charset);
        Map<String, Indi> indis = new LinkedHashMap<>();
        Map<String, Fam> fams = new LinkedHashMap<>();
        Map<String, Integer> notRead = new LinkedHashMap<>();
        Map<String, String> sourceTitles = new LinkedHashMap<>();   // @S1@ → its title, for the citations events carry
        Map<String, House> houses = new LinkedHashMap<>();
        String type = null, headDate = "";
        String l1 = null, l2 = null, lastFamc = null, srcRec = null, exid = null;
        Indi indi = null; Fam fam = null; Ev ev = null; House house = null; GName nameNow = null;
        boolean primaryName = false;
        Map<String, String> quality = Map.of("0", "unreliable", "1", "questionable", "2", "secondary evidence", "3", "direct and primary evidence");
        for (String raw : text.split("\r\n|\r|\n")) {
            String line = raw.strip().replace("﻿", "");
            if (line.isEmpty()) continue;
            String[] p = line.split(" ", 3);
            int level;
            try { level = Integer.parseInt(p[0]); } catch (NumberFormatException e) { continue; }
            if (level == 0) {
                indi = null; fam = null; ev = null; house = null; nameNow = null; lastFamc = null; srcRec = null; l1 = null; l2 = null; exid = null;
                String xref;
                if (p.length >= 3 && p[1].startsWith("@")) { xref = p[1]; type = p[2].strip(); }
                else { xref = null; type = p.length > 1 ? p[1] : ""; }
                if ("INDI".equals(type) && xref != null) { indi = new Indi(xref); indis.put(xref, indi); }
                if ("FAM".equals(type) && xref != null) { fam = new Fam(xref); fams.put(xref, fam); }
                if ("_HOUSE".equals(type) && xref != null) { house = new House(xref); houses.put(xref, house); }
                if ("SOUR".equals(type)) srcRec = xref;
                continue;
            }
            String tag = p.length > 1 ? p[1] : "";
            String val = p.length > 2 ? p[2] : "";
            if (level == 1) { l1 = tag; l2 = null; } else if (level == 2) l2 = tag;
            if ("HEAD".equals(type)) { if (level == 1 && "DATE".equals(tag)) headDate = val.strip(); continue; }
            if (indi != null) {
                if (level == 1) {
                    ev = null; exid = null; nameNow = null;
                    switch (tag) {
                        case "NAME" -> {
                            String n = personName(val);
                            primaryName = indi.name.isEmpty();
                            if (primaryName) { indi.name = n; indi.given = val.contains("/") ? val.substring(0, val.indexOf('/')).strip() : ""; }
                            String[] parts = parts(val);
                            nameNow = new GName("NAME", val, primaryName, n, parts[0], parts[1]);
                            indi.names.add(nameNow);
                            ev = nameNow.ev;   // the citations under the name are the name's
                        }
                        case "SEX" -> indi.sex = val;
                        case "FAMC" -> { indi.famc.add(val); lastFamc = val; }
                        case "FAMS" -> indi.fams.add(val);
                        case "NOTE" -> { if (!val.isBlank() && !val.startsWith("@")) indi.notes.add(val.strip()); }
                        case "_TODO" -> { if (!val.isBlank()) indi.todos.add(val.strip()); }
                        case "ADOP" -> { ev = new Ev("ADOP", ""); indi.adoptions.add(ev); }
                        case "_MEMBER" -> { ev = new Ev("_MEMBER", ""); ev.more.put("house", val.strip()); indi.members.add(ev); }
                        case "_FSFTID" -> { if (!val.isBlank()) indi.ids.add(new PersonIds.Id("familysearch", val.strip(), "the family file's _FSFTID")); }
                        case "EXID" -> exid = val.strip();
                        case "ASSO" -> { if (val.strip().startsWith("@")) indi.assoc.add(new String[]{val.strip(), ""}); }
                        case "_UID", "UID", "_FTSID" -> { if (indi.uid.isEmpty()) indi.uid = val.strip(); }
                        default -> {
                            if (EVENTS.contains(tag)) { ev = new Ev(tag, tag.equals("OCCU") ? val.strip() : ""); indi.events.add(ev); }
                            else if (!BOOKKEEPING.contains(tag)) notRead.merge("INDI " + tag, 1, Integer::sum);
                        }
                    }
                } else if (level == 2 && "NAME".equals(l1) && nameNow != null) {
                    GName n = nameNow;
                    String v = val.strip();
                    switch (tag) {
                        case "NSFX" -> { if (primaryName && !v.isEmpty() && !indi.name.endsWith(v)) { indi.name = indi.name + " " + v; n.written = indi.name; } }
                        case "GIVN" -> { if (!v.isEmpty()) n.given = v; }
                        case "SURN" -> { if (!v.isEmpty()) n.family = v; }
                        case "TYPE" -> n.type = v;
                        // the same name in another script: a romanised form, a reading, a transliteration with its language
                        case "ROMN", "FONE", "TRAN" -> { String f = formText(v); if (!f.isEmpty()) n.forms.add(new FamilyNameHistory.Form(f, FamilyForms.lang(f))); }
                        case "_AKA" -> { if (!v.isEmpty()) { String[] pa = parts(v); indi.names.add(new GName("_AKA", v, false, personName(v), pa[0], pa[1])); } }
                        case "_MARNM", "_MARRIED" -> {
                            // a married name is often the new family name alone
                            if (!v.isEmpty()) {
                                String[] pa = v.contains("/") ? parts(v) : new String[]{n.given, v};
                                indi.names.add(new GName("_MARNM", v, false, v.contains("/") ? personName(v) : personName(n.given + " /" + v + "/"), pa[0], pa[1]));
                            }
                        }
                        case "_NAMEKIND" -> n.kind = v;
                        case "_NAMEDATE" -> n.nameDate = v;
                        case "DATE" -> n.date = v;
                        case "_NAMEEVENT" -> n.event = v;
                        case "SOUR" -> { if (!v.isEmpty()) n.ev.cites.add(v); }
                        default -> { }
                    }
                } else if (level == 3 && "NAME".equals(l1) && nameNow != null && !"SOUR".equals(l2)) {
                    GName n = nameNow;
                    // GEDCOM 7: OTHER with its PHRASE; the language of a transliteration
                    if ("TYPE".equals(l2) && "PHRASE".equals(tag)) n.phrase = val.strip();
                    if (("TRAN".equals(l2) || "ROMN".equals(l2) || "FONE".equals(l2)) && "LANG".equals(tag) && !n.forms.isEmpty() && !val.isBlank()) {
                        FamilyNameHistory.Form last = n.forms.get(n.forms.size() - 1);
                        n.forms.set(n.forms.size() - 1, new FamilyNameHistory.Form(last.text(), val.strip()));
                    }
                } else if (level == 2 && ("NOTE".equals(l1) || "_TODO".equals(l1)) && ("CONT".equals(tag) || "CONC".equals(tag))) {
                    List<String> into = "NOTE".equals(l1) ? indi.notes : indi.todos;
                    if (!into.isEmpty()) { int last = into.size() - 1; into.set(last, into.get(last) + ("CONT".equals(tag) ? " " : "") + val); }
                } else if (level == 2 && "ASSO".equals(l1) && ("RELA".equals(tag) || "ROLE".equals(tag)) && !indi.assoc.isEmpty()) {
                    indi.assoc.get(indi.assoc.size() - 1)[1] = val.strip();
                } else if (level == 2 && "FAMC".equals(l1) && ("PEDI".equals(tag) || "_PEDI".equals(tag)) && lastFamc != null) {
                    indi.pedigree.put(lastFamc, val.strip());
                    if (val.toLowerCase(Locale.ROOT).startsWith("adopt")) indi.adoptedInto.add(lastFamc);
                } else if (level == 2 && exid != null && "TYPE".equals(tag)) {
                    String site = PersonIds.siteOf(val);
                    indi.ids.add(new PersonIds.Id(site.isEmpty() ? val.strip() : site, exid, val.strip()));
                } else if (level == 2 && ev != null) {
                    if ("DATE".equals(tag)) date(ev, val.strip());
                    if ("PLAC".equals(tag)) ev.place = val.strip();
                    if ("SOUR".equals(tag)) ev.cites.add(val.strip());
                    if ("AGE".equals(tag)) ev.age = val.strip();                                     // the person's age at the event, as the record gave it
                    if ("TYPE".equals(tag)) ev.type = val.strip();                                   // what kind of adoption: 婿養子, as heir
                    if ("FAMC".equals(tag) && "ADOP".equals(ev.tag)) { indi.adoptedInto.add(val); ev.famc = val.strip(); }
                    if ("_MEMBER".equals(ev.tag) && tag.startsWith("_")) ev.more.put(tag, val.strip());   // _HOW, _LEFT, _ROLE
                } else if (level == 3 && ev != null && "ADOP".equals(ev.tag) && "FAMC".equals(l2) && "ADOP".equals(tag)) {
                    ev.by = val.strip().toUpperCase(Locale.ROOT);                                    // which parent adopted: HUSB, WIFE or BOTH
                } else if (level >= 3 && ev != null && "SOUR".equals(l2) && !ev.cites.isEmpty()) {
                    int last = ev.cites.size() - 1;
                    if (("PAGE".equals(tag) || "TEXT".equals(tag)) && !val.isBlank()) ev.cites.set(last, ev.cites.get(last) + " " + val.strip());
                    // the page, kept apart as well: two claims can cite one page of one source
                    if ("PAGE".equals(tag) && level == 3 && !val.isBlank()) ev.cites.set(last, ev.cites.get(last) + " \u0004" + val.strip() + "\u0005");
                    if ("QUAY".equals(tag) && quality.containsKey(val.strip())) ev.cites.set(last, ev.cites.get(last) + " \u0001the file rates it " + quality.get(val.strip()));
                    // SOUR.DATA.DATE: when the source itself wrote the entry down, which a birth read from a death record shows far from the birth
                    if ("DATE".equals(tag) && level == 4 && !val.isBlank()) ev.cites.set(last, ev.cites.get(last) + " \u0002" + val.strip() + "\u0003");
                }
            } else if (srcRec != null) {
                if (level == 1 && ("TITL".equals(tag) || "ABBR".equals(tag)) && !sourceTitles.containsKey(srcRec)) sourceTitles.put(srcRec, val.strip());
            } else if (house != null) {
                if (level == 1 && "NAME".equals(tag) && !val.isBlank()) house.names.add(val.strip());
                if (level == 1 && "_SEAT".equals(tag)) house.seat = val.strip();
                if (level == 1 && "_BRANCH".equals(tag)) house.branch = val.strip();
            } else if (fam != null) {
                if (level == 1) {
                    ev = null;
                    switch (tag) {
                        case "HUSB" -> fam.husb = val.strip();
                        case "WIFE" -> fam.wife = val.strip();
                        case "CHIL" -> fam.chil.add(val.strip());
                        case "MARR" -> ev = fam.marr;
                        default -> { if (!BOOKKEEPING.contains(tag)) notRead.merge("FAM " + tag, 1, Integer::sum); }
                    }
                } else if (level == 2 && "CHIL".equals(l1) && !fam.chil.isEmpty() && ("_FREL".equals(tag) || "_MREL".equals(tag))) {
                    fam.rel.computeIfAbsent(fam.chil.get(fam.chil.size() - 1), k -> new String[]{"", ""})["_FREL".equals(tag) ? 0 : 1] = val.strip();
                } else if (level == 2 && ev != null) {
                    if ("DATE".equals(tag)) ev.date = val.strip();
                    if ("PLAC".equals(tag)) ev.place = val.strip();
                    if ("SOUR".equals(tag)) ev.cites.add(val.strip());
                } else if (level >= 3 && ev != null && "SOUR".equals(l2) && !ev.cites.isEmpty() && ("PAGE".equals(tag) || "TEXT".equals(tag) || ("DATE".equals(tag) && level == 4)) && !val.isBlank()) {
                    int last = ev.cites.size() - 1;
                    ev.cites.set(last, ev.cites.get(last) + ("DATE".equals(tag) ? " \u0002" + val.strip() + "\u0003" : " " + val.strip()));
                    if ("PAGE".equals(tag) && level == 3) ev.cites.set(last, ev.cites.get(last) + " \u0004" + val.strip() + "\u0005");
                }
            }
        }
        for (Indi i : indis.values()) settle(i, fams, problems);
        for (Indi i : indis.values()) for (String[] a : i.assoc) if (associate(a[1]) == null) notRead.merge("INDI ASSO", 1, Integer::sum);
        for (Indi i : indis.values()) for (Ev e : i.events) if (!e.movedFrom.isEmpty() && !i.name.isBlank())
            problems.add("The " + (e.movedFrom.equals("BIRT") ? "birth" : "death") + " date of " + i.name + " in the file reads \"" + e.written + "\", which is the date of a "
                    + (e.tag.equals("BURI") ? "burial" : "baptism") + ". It was filed as a " + (e.tag.equals("BURI") ? "burial" : "baptism") + ", not as a " + (e.movedFrom.equals("BIRT") ? "birth" : "death") + ".");
        return new Parsed(indis, fams, sourceTitles, charset[0], headDate, problems, notRead, houses);
    }

    /**
     * What the whole record says of each of its names and its adoptions, once every line of the file is read. A NAME the file calls a
     * spelling is a written form of the name it spells. A later name the file writes as a family name alone, which takes the place of another
     * ("/Ellis/" as a married name), is the person's given name under that family name. The first NAME, when the file gives it no kind, is
     * the name at birth when another name of the person is one the file says came later (a married name, a name taken on adoption). An
     * adoption the file records without its family belongs to the one family the file says adopted the person; when it does not say which,
     * the import says so.
     */
    private static void settle(Indi i, Map<String, Fam> fams, List<String> problems) {
        if (!i.names.isEmpty()) {
            // a spelling: a form of the name it spells, the one of the same given name spelt most alike
            for (GName n : new ArrayList<>(i.names)) {
                if (!spelling(n)) continue;
                GName into = null;
                int best = Integer.MAX_VALUE;
                for (GName o : i.names) {
                    if (o == n || spelling(o)) continue;
                    int d = distance(Vocabulary.norm(o.written), Vocabulary.norm(n.written)) + (Vocabulary.norm(o.given).equals(Vocabulary.norm(n.given)) ? 0 : 1000);
                    if (d < best) { best = d; into = o; }
                }
                if (into == null) continue;
                List<FamilyNameHistory.Form> more = new ArrayList<>(List.of(new FamilyNameHistory.Form(n.written, FamilyForms.lang(n.written))));
                more.addAll(n.forms);
                for (FamilyNameHistory.Form f : more) if (!f.text().isBlank() && !f.text().equals(into.written) && into.forms.stream().noneMatch(x -> x.text().equals(f.text()))) into.forms.add(f);
                for (String c : n.ev.cites) if (!into.ev.cites.contains(c)) into.ev.cites.add(c);
                into.spelt = true;
                i.names.remove(n);
            }
            GName first = i.names.get(0);
            // a later name that gives only a family name, in place of another: the person's given name under it
            for (GName n : i.names) {
                if (n == first || !n.given.isBlank() || n.family.isBlank() || first.given.isBlank() || !FamilyNameHistory.replaces(nameKind(n))) continue;
                n.given = first.given;
                n.written = personName(first.given + " /" + n.family + "/");
            }
            // the first NAME, of no kind, before a name the file says came later: the name at birth
            if (first.first && first.type.isBlank() && first.kind.isBlank() && first.phrase.isBlank()
                    && i.names.stream().noneMatch(n -> n != first && nameKind(n).equals("birth"))
                    && i.names.stream().anyMatch(n -> n != first && !Vocabulary.norm(n.written).equals(Vocabulary.norm(first.written)) && !nameKind(n).equals("unknown") && FamilyNameHistory.replaces(nameKind(n))))
                first.inferred = "birth";
        }
        // an adoption with no family: the one family the file says adopted the person, and no other adoption names
        List<Ev> loose = i.adoptions.stream().filter(a -> a.famc.isBlank()).toList();
        if (loose.isEmpty()) return;
        Set<String> into = new LinkedHashSet<>();
        for (Fam f : fams.values()) {
            if (!f.chil.contains(i.xref) && !i.famc.contains(f.xref)) continue;
            String[] rel = f.rel.getOrDefault(i.xref, new String[]{"", ""});
            if (i.pedigree.getOrDefault(f.xref, "").toLowerCase(Locale.ROOT).startsWith("adopt") || rel[0].toLowerCase(Locale.ROOT).startsWith("adopt") || rel[1].toLowerCase(Locale.ROOT).startsWith("adopt"))
                into.add(f.xref);
        }
        for (Ev a : i.adoptions) if (!a.famc.isBlank()) into.remove(a.famc);
        if (loose.size() == 1 && into.size() == 1) { loose.get(0).famc = into.iterator().next(); return; }
        String who = i.name.isBlank() ? i.xref : i.name;
        for (Ev a : loose) {
            List<String> said = new ArrayList<>();
            if (!a.date.isBlank()) said.add(a.date.strip());
            if (!a.type.isBlank()) said.add(a.type.strip());
            String what = "The file records that " + who + " was adopted" + (said.isEmpty() ? "" : " (" + String.join(", ", said) + ")");
            if (into.isEmpty()) problems.add(what + ", but it names no family that adopted " + who + ", so the library did not file that adoption. The parents are filed as the file gives them.");
            else if (loose.size() == 1) problems.add(what + ", but not by which of the " + into.size() + " families that adopted " + who + " in the file. The library filed each of those adoptions without that "
                    + (said.isEmpty() ? "event" : "date and kind") + ".");
            else problems.add(what + ", but not by which family, and it records " + loose.size() + " such adoptions of " + who + ". The library filed the adoptions without "
                    + (said.isEmpty() ? "them" : "that date and kind") + ".");
        }
    }

    /** Whether a later NAME is only a spelling of another: its TYPE says so, and says no kind of name. */
    private static boolean spelling(GName n) {
        return !n.first && n.kind.isBlank() && SPELLING.matcher(n.type + " " + n.phrase).find() && nameKind(n).equals("unknown");
    }

    /** How many letters must change to make one word the other. */
    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int k = 0; k <= b.length(); k++) prev[k] = k;
        for (int x = 1; x <= a.length(); x++) {
            cur[0] = x;
            for (int k = 1; k <= b.length(); k++) cur[k] = Math.min(Math.min(cur[k - 1] + 1, prev[k] + 1), prev[k - 1] + (a.charAt(x - 1) == b.charAt(k - 1) ? 0 : 1));
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    /**
     * A GEDCOM name's parts, {given, family}: the words between the slashes are the family name, the words before them the given name (or
     * the words after them, in a name written family name first: "/Morita/ Kenji"); {"", ""} for a name with no slashes.
     */
    static String[] parts(String gedcomName) {
        Matcher m = Pattern.compile("^(.*?)/([^/]*)/(.*)$").matcher(gedcomName == null ? "" : gedcomName.strip());
        if (!m.find()) return new String[]{"", ""};
        String given = m.group(1).isBlank() ? m.group(3).strip() : m.group(1).strip();
        return new String[]{given, m.group(2).strip()};
    }

    /**
     * A written form of a name as the library keeps it: as {@link #personName} writes a name, except a reading in kana, which keeps a space
     * between the family name and the given name ("えんどう けんじ"), so that its romaji can be spelt word by word.
     */
    static String formText(String gedcomName) {
        String v = gedcomName == null ? "" : gedcomName.strip();
        String[] p = parts(v);
        if (!p[1].isEmpty() && !p[0].isEmpty() && FamilyForms.script(p[1] + p[0]).equals("kana")) return p[1] + " " + p[0].replace(" ", "");
        return personName(v);
    }

    /** A date as the event keeps it; a birth date that says it is a baptism's ("Bapt 18 Dec 1690") moves the event to the baptism. */
    private static void date(Ev ev, String val) {
        Matcher m = LABELLED.matcher(val);
        if (m.matches()) {
            String w = m.group(1).toLowerCase(Locale.ROOT);
            boolean baptism = w.startsWith("bap") || w.startsWith("chr");
            if (ev.tag.equals("BIRT") && baptism) { ev.movedFrom = ev.tag; ev.written = val; ev.tag = w.startsWith("chr") ? "CHR" : "BAPM"; ev.date = m.group(2).strip(); return; }
            if (ev.tag.equals("DEAT") && !baptism) { ev.movedFrom = ev.tag; ev.written = val; ev.tag = "BURI"; ev.date = m.group(2).strip(); return; }
        }
        ev.date = val;
    }

    /**
     * The file's text. A byte order mark or UTF-16's zero bytes settle it; otherwise text that reads as UTF-8 is UTF-8, whatever the
     * header says, and any other text is read in the character set the header names ({@code 1 CHAR}), Windows-1252 when it names none.
     */
    static String decode(byte[] b, List<String> problems, String[] used) {
        if (b.length >= 3 && (b[0] & 0xff) == 0xEF && (b[1] & 0xff) == 0xBB && (b[2] & 0xff) == 0xBF) { used[0] = "UTF-8"; return new String(b, 3, b.length - 3, StandardCharsets.UTF_8); }
        if (b.length >= 2 && (b[0] & 0xff) == 0xFE && (b[1] & 0xff) == 0xFF) { used[0] = "UTF-16"; return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE); }
        if (b.length >= 2 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xFE) { used[0] = "UTF-16"; return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE); }
        if (b.length >= 2 && b[0] == 0 && b[1] == '0') { used[0] = "UTF-16"; return new String(b, StandardCharsets.UTF_16BE); }
        if (b.length >= 2 && b[0] == '0' && b[1] == 0) { used[0] = "UTF-16"; return new String(b, StandardCharsets.UTF_16LE); }
        String head = new String(b, 0, Math.min(b.length, 4096), StandardCharsets.ISO_8859_1);
        Matcher cm = Pattern.compile("(?m)^\\s*1\\s+CHAR(?:ACTER)?\\s+(.+?)\\s*$").matcher(head);
        String declared = cm.find() ? cm.group(1).strip().toUpperCase(Locale.ROOT) : "";
        Matcher vm = Pattern.compile("(?m)^\\s*1\\s+CHAR.*\\R\\s*2\\s+VERS\\s+(.+?)\\s*$").matcher(head);
        String vers = vm.find() ? vm.group(1).toUpperCase(Locale.ROOT) : "";
        boolean ascii = true;
        for (byte x : b) if ((x & 0x80) != 0) { ascii = false; break; }
        if (ascii) { used[0] = declared.isEmpty() ? "ASCII" : declared; return new String(b, StandardCharsets.US_ASCII); }
        try {
            String s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
            if (!declared.isEmpty() && !declared.startsWith("UTF") && !declared.equals("UNICODE"))
                problems.add("The file says its letters are written in the " + declared + " character set, but they are written in UTF-8. The library read them as UTF-8.");
            used[0] = "UTF-8";
            return s;
        } catch (CharacterCodingException notUtf8) { /* read as the header says */ }
        if (declared.equals("ANSEL")) { used[0] = "ANSEL"; return ansel(b); }
        String cs = switch (declared) {
            case "MACINTOSH" -> "x-MacRoman";
            case "IBMPC", "IBM", "IBM-PC", "OEM", "CP437" -> "IBM437";
            case "CP850", "MSDOS", "MS-DOS", "IBM-DOS" -> "IBM850";
            case "CP1250", "WINDOWS-1250" -> "windows-1250";
            case "CP1251", "WINDOWS-1251" -> "windows-1251";
            case "ISO-8859-1", "ISO8859-1", "ISO8859", "LATIN-1", "LATIN1" -> "ISO-8859-1";
            case "ISO-8859-2", "ISO8859-2", "LATIN-2", "LATIN2" -> "ISO-8859-2";
            case "ASCII" -> vers.contains("MAC") ? "x-MacRoman" : "windows-1252";
            default -> "windows-1252";
        };
        if (!Set.of("ANSI", "WINDOWS", "IBM WINDOWS", "IBM_WINDOWS", "CP1252", "WINDOWS-1252", "ASCII").contains(declared) && cs.equals("windows-1252"))
            problems.add((declared.isEmpty() ? "The file does not say which character set its letters are written in" : "The file says its letters are written in " + declared + ", which the library does not know,")
                    + " and they are not UTF-8. The library read them as Windows-1252, the most common other one. If names with accents look wrong, save the file from your family-tree program as UTF-8 and import it again.");
        used[0] = cs;
        return new String(b, Charset.isSupported(cs) ? Charset.forName(cs) : StandardCharsets.ISO_8859_1);
    }

    /** ANSEL's letters from 0xA1 to 0xCF; 0 where it has none. */
    private static final char[] ANSEL_LETTERS = {
            'Ł', 'Ø', 'Đ', 'Þ', 'Æ', 'Œ', 'ʹ', '·', '♭', '®', '±', 'Ơ', 'Ư', 'ʼ', 0,
            'ʻ', 'ł', 'ø', 'đ', 'þ', 'æ', 'œ', 'ʺ', 'ı', '£', 'ð', 0, 'ơ', 'ư', '□', '■',
            '°', 'ℓ', '℗', '©', '♯', '¿', '¡', 'ß', '€', 0, 0, 0, 0, 'e', 'o', 'ß'};
    /** ANSEL's accents from 0xE0 to 0xFF, written before the letter they go on; 0 where it has none. */
    private static final char[] ANSEL_MARKS = {
            '̉', '̀', '́', '̂', '̃', '̄', '̆', '̇', '̈', '̌', '̊', '͡', 0, '̕', '̋', '̐',
            '̧', '̨', '̣', '̤', '̥', '̳', '̲', '̦', '̜', '̮', '͠', 0, 0, 0, '̓', '̸'};

    /** ANSEL (Z39.47), the character set of older GEDCOM files: an accent comes before its letter, so it is moved after it and the two joined. */
    static String ansel(byte[] b) {
        StringBuilder out = new StringBuilder(b.length), marks = new StringBuilder();
        for (byte x : b) {
            int v = x & 0xff;
            if (v >= 0xE0) { char m = ANSEL_MARKS[v - 0xE0]; if (m != 0) marks.append(m); continue; }
            char c = v < 0x80 ? (char) v : v >= 0xA1 && v <= 0xCF && ANSEL_LETTERS[v - 0xA1] != 0 ? ANSEL_LETTERS[v - 0xA1] : '�';
            out.append(c).append(marks);
            marks.setLength(0);
        }
        return Normalizer.normalize(out, Normalizer.Form.NFC);
    }

    // ---- who is who ----

    private static final Pattern EDITION = Pattern.compile("^GEDCOM (\\S+ [A-Z_]+)");

    /** A person's name without the bracket that tells two people of one name apart. */
    static String bare(String label) { return label == null ? "" : label.replaceAll("\\s*[(（][^)）]*[)）]\\s*$", "").strip(); }

    static Path labelsFile(LibraryStore store) { return Graph.dir(store).resolve("gedcom-people.tsv"); }

    /**
     * The name each person in the file is filed under. One INDI record is one person: people of one name in the file are told apart by
     * the year of their birth, their baptism or their death, or else by their number in the file ("Tom Ellis (I12 in tree.ged)"). A person
     * whose birth year is more than two years from that of somebody of the same name already in the library is kept apart the same way.
     * A name given before, to the same record of the same file or to the same UID, is given again, so a re-import lands on the same people.
     */
    static Map<String, String> labels(LibraryStore store, Parsed p, String fileName, String source, Graph held, List<Finding> all, List<String> apart) throws IOException {
        Map<String, String> saved = new HashMap<>();
        if (Files.exists(labelsFile(store))) for (String line : Files.readAllLines(labelsFile(store), StandardCharsets.UTF_8)) {
            String[] x = line.split("\t");
            if (x.length < 4) continue;
            saved.put("file:" + x[0] + "|" + x[1], x[3]);
            if (!x[2].isBlank()) saved.put("uid:" + x[2], x[3]);
        }
        Map<String, Set<String>> earlier = earlierByRecord(held, all, source), earlierNames = earlierByRecord(all, source, Vocabulary::norm);
        Map<String, FamilyDate> heldBorn = birthDates(held, all);
        Map<String, List<Indi>> byName = new LinkedHashMap<>();
        for (Indi i : p.indis().values()) if (!i.name.isBlank()) byName.computeIfAbsent(Vocabulary.norm(i.name), k -> new ArrayList<>()).add(i);
        Map<String, String> out = new LinkedHashMap<>();
        for (Indi i : p.indis().values()) {
            if (i.name.isBlank()) continue;
            String s = i.uid.isEmpty() ? null : saved.get("uid:" + i.uid);
            if (s == null) s = saved.get("file:" + fileName + "|" + i.xref);
            if (s != null && (Vocabulary.norm(bare(s)).equals(Vocabulary.norm(i.name)) || held.node(held.nodeIdOf(s)) != null && held.nodeIdOf(s).equals(held.nodeIdOf(i.name)))) out.put(i.xref, s);
        }
        Map<String, String> byRecord = new HashMap<>(out);
        // how many records of the file give each name or written form: one that two records give tells nobody apart
        Map<String, Integer> inFile = new HashMap<>();
        for (Indi i : p.indis().values()) { Set<String> mine = new HashSet<>(); mine.add(Vocabulary.norm(i.name)); for (String o : otherNamesOf(i)) mine.add(Vocabulary.norm(o)); for (String m : mine) inFile.merge(m, 1, Integer::sum); }
        Map<String, String> known = FamilyNames.known(held);
        for (List<Indi> same : byName.values()) {
            if (same.size() > 1) {
                Map<String, String> want = new LinkedHashMap<>();
                for (Indi i : same) if (!out.containsKey(i.xref)) want.put(i.xref, i.name + " (" + tellApart(i, fileName) + ")");
                Set<String> taken = new HashSet<>();
                for (Indi i : same) if (out.containsKey(i.xref)) taken.add(Vocabulary.norm(out.get(i.xref)));
                for (Indi i : same) {
                    String w = want.get(i.xref);
                    if (w == null) continue;
                    boolean clash = taken.contains(Vocabulary.norm(w)) || want.values().stream().filter(x -> Vocabulary.norm(x).equals(Vocabulary.norm(w))).count() > 1;
                    out.put(i.xref, clash ? i.name + " (" + i.xref.replace("@", "") + " in " + fileName + ")" : w);
                }
                apart.add("The file has " + same.size() + " people named " + same.get(0).name + ". Each is a person of their own: " + String.join(", ", same.stream().map(i -> out.get(i.xref)).toList()) + ".");
                // filed then under the bare name as it is written: a bare name the owner joined into one of the people since resolves to that
                // person, whose claims from this file are this import's own, and is no sign of an earlier import's mistake
                String bare = Vocabulary.norm(same.get(0).name);
                if (same.stream().anyMatch(i -> earlierNames.getOrDefault(i.xref, Set.of()).contains(bare)))
                    apart.add("An earlier import of this file took the people named " + same.get(0).name + " for one person, and what it filed then is still under that name. To take it away and keep only the people this import filed, give the command "
                            + "researchzosho genealogy reset --from \"" + source.replaceFirst("^file://", "") + "\" (it takes back the drafts read from this file), then import the file again.");
                continue;
            }
            Indi i = same.get(0);
            if (out.containsKey(i.xref)) continue;
            // one of this name in the file, and somebody of the same name already in the library whose birth year sets them apart
            String id = held.nodeIdOf(i.name);
            Graph.Node there = held.node(id);
            if (there == null) {
                // nobody of the first name yet: the record's other names and written forms are this one person's, so the one person of the
                // library one of them names is this record (遠藤健二, later 森田健二, is the 森田健二 a book already gave) when a second fact
                // agrees: the same birth year, or a parent, a husband or wife, or a child the library already has for that person and that is
                // that same entry by more than a name. A shared name alone joins nobody: a son's wife can carry his mother's married name and
                // have a husband of his father's name, and a grandson his grandfather's name
                Met met = byOtherName(held, known, p, i, inFile, heldBorn, byRecord, earlier);
                if (met != null && met.agrees() != null) {
                    out.put(i.xref, met.label());
                    apart.add(i.name + " in the file is filed under " + met.label() + ", who is already in your library, because the file gives " + i.name + " that name too. " + met.agrees());
                    continue;
                }
                if (met != null) apart.add(i.name + " in the file also has the name " + met.name() + ", which is the name of another entry in your library. Nothing else the file says shows that they are one person,"
                        + " such as the same birth year, or a parent, husband, wife or child who is the same person in the file and in your library, so " + i.name + " is kept as an entry of its own, and the library will ask you whether the two are one person."
                        + " The command researchzosho genealogy who asks the questions that wait, one at a time.");
            }
            // two people only when no year of the one date fits the other, with two years to spare: "BET 1840 AND 1860" fits 1855
            FamilyDate mine = i.date("BIRT"), theirs = heldBorn.get(id);
            if (there != null && "person".equals(there.kind()) && mine != null && theirs != null && FamilyDate.apart(mine, theirs, 2) && !earlier.getOrDefault(i.xref, Set.of()).contains(id)) {
                String l = i.name + " (born " + mine.phrase() + ")";
                out.put(i.xref, l);
                apart.add(i.name + " in the file was born " + mine.in() + ", and the " + there.label() + " already in your library was born " + theirs.in() + ". They are two people, so the one from the file is filed as " + l + ".");
            } else out.put(i.xref, filedUnder(i, there));
        }
        return out;
    }

    /**
     * The name a record is filed under when its first name leads to an entry of the library: the entry's label when that is another name of
     * the entry and the record gives the label as one of its own names too (the library's own export, which writes the latest name first,
     * read back), so the facts join the entry's own; else the first name as the file writes it.
     */
    private static String filedUnder(Indi i, Graph.Node there) {
        if (there == null || !"person".equals(there.kind()) || Vocabulary.norm(bare(there.label())).equals(Vocabulary.norm(i.name))) return i.name;
        for (String o : otherNamesOf(i)) if (Vocabulary.norm(o).equals(Vocabulary.norm(bare(there.label())))) return there.label();
        return i.name;
    }

    /**
     * What a record's other names meet in the library: the one person they name ({@code id}, {@code label}), the name of the record that
     * names them, and the sentence that says what else the file and the library agree on, null when nothing else does.
     */
    private record Met(String id, String label, String name, String agrees) { }

    /**
     * The one person in the library that a record's other names or written forms name, when its first name names nobody: each name or form
     * that no other record of the file gives, looked up as the library looks up a name (as written, or in another order of the same words).
     * With what else the file and the library agree on: the same birth year, else a parent, a husband or wife, or a child the library
     * already records in that relation to that person, when that relative is the same entry by more than a name ({@link #sameEntry}).
     * Null when none names a person, when they name two, or when the birth years set them apart.
     */
    private static Met byOtherName(Graph held, Map<String, String> known, Parsed p, Indi i, Map<String, Integer> inFile, Map<String, FamilyDate> heldBorn,
                                   Map<String, String> byRecord, Map<String, Set<String>> earlier) {
        Map<String, String> ids = new LinkedHashMap<>();
        for (String o : otherNamesOf(i)) {
            if (inFile.getOrDefault(Vocabulary.norm(o), 0) > 1 || !FamilyNames.keepAsOtherName(i.name, o)) continue;
            String at = held.nodeIdOf(FamilyNames.resolve(held, known, o));
            Graph.Node n = held.node(at);
            if (n != null && "person".equals(n.kind()) && !FamilyQuestions.placeholder(n.label())) ids.putIfAbsent(at, o);
        }
        if (ids.size() != 1) return null;
        String id = ids.keySet().iterator().next();
        String label = held.node(id).label(), name = ids.get(id);
        FamilyDate mine = i.date("BIRT"), theirs = heldBorn.get(id);
        if (mine != null && theirs != null && FamilyDate.apart(mine, theirs, 2)) return null;
        if (sameYear(mine, theirs)) return new Met(id, label, name, "The birth years agree: " + mine.phrase() + " in the file and " + theirs.phrase() + " in your library.");
        return new Met(id, label, name, sameRelative(held, p, i, id, label, x -> sameEntry(held, p, x, byRecord, earlier, inFile, heldBorn)));
    }

    /** Whether two birth dates give one and the same year, each written as a year and not a range or an estimate. */
    private static boolean sameYear(FamilyDate a, FamilyDate b) { return a != null && b != null && a.exact() && b.exact() && a.year() == b.year(); }

    /**
     * The sentence that says which relative the file gives a record that the library already records in the same relation to a person:
     * a parent as a parent, a husband or wife as a husband or wife, a child as a child, each relative of the file the entry {@code entry}
     * says it is. Null when the file gives none of those.
     */
    private static String sameRelative(Graph held, Parsed p, Indi i, String id, String label, Function<String, String> entry) {
        Map<String, Set<String>> kin = new HashMap<>();
        for (Graph.Edge e : held.edges()) {
            if (FamilyKin.gone(e) || e.from().equals(e.to()) || (!e.from().equals(id) && !e.to().equals(id))) continue;
            boolean from = e.from().equals(id);
            String as = switch (e.predicate()) {
                case "parent-of", "step-parent-of" -> from ? "child" : "parent";
                case "child-of", "adopted-by", "foster-child-of" -> from ? "parent" : "child";
                case "married-to" -> "spouse";
                default -> null;
            };
            if (as != null) kin.computeIfAbsent(as, k -> new HashSet<>()).add(from ? e.to() : e.from());
        }
        if (kin.isEmpty()) return null;
        for (Fam f : p.fams().values()) {
            if (f.husb.equals(i.xref) || f.wife.equals(i.xref)) {
                String hit = heldAs(held, entry, f.husb.equals(i.xref) ? f.wife : f.husb, kin.get("spouse"));
                if (hit != null) return "The file and your library also give " + label + " the same husband or wife, " + hit + ".";
                for (String c : f.chil) if ((hit = heldAs(held, entry, c, kin.get("child"))) != null) return "The file and your library also give " + label + " the same child, " + hit + ".";
            }
            if (f.chil.contains(i.xref)) for (String parent : new String[]{f.husb, f.wife}) {
                String hit = heldAs(held, entry, parent, kin.get("parent"));
                if (hit != null) return "The file and your library also give " + label + " the same parent, " + hit + ".";
            }
        }
        return null;
    }

    /** The label of the one of {@code ids} that a record of the file is, as {@code entry} says; null when it is none of them. */
    private static String heldAs(Graph held, Function<String, String> entry, String xref, Set<String> ids) {
        String at = entry.apply(xref);
        return at == null || ids == null || !ids.contains(at) || held.node(at) == null ? null : held.node(at).label();
    }

    /**
     * The entry of the library a relative of the file is by more than a name, null when nothing but a name says so: the entry the import
     * files the relative under (the name it filed that record under before, else the first name) when the library already holds what this
     * very record of this file said under that entry, or when the birth years are the same on both sides and no other record of the file
     * has the relative's name. A relative of the same name and no birth year, or of another birth year, may be a son named for his father.
     */
    private static String sameEntry(Graph held, Parsed p, String xref, Map<String, String> byRecord, Map<String, Set<String>> earlier, Map<String, Integer> inFile,
                                    Map<String, FamilyDate> heldBorn) {
        Indi r = p.indis().get(xref);
        if (r == null || r.name.isBlank()) return null;
        String at = held.nodeIdOf(byRecord.getOrDefault(xref, r.name));
        if (held.node(at) == null) return null;
        if (earlier.getOrDefault(xref, Set.of()).contains(at)) return at;
        return inFile.getOrDefault(Vocabulary.norm(r.name), 0) == 1 && sameYear(r.date("BIRT"), heldBorn.get(at)) ? at : null;
    }

    /** What tells one person of a shared name from the others: a birth, baptism or death year, or else their number in the file. */
    private static String tellApart(Indi i, String fileName) {
        FamilyDate d = i.date("BIRT");
        if (d != null) return "born " + d.phrase();
        d = i.date("CHR", "BAPM");
        if (d != null) return "baptised " + d.phrase();
        d = i.date("DEAT");
        if (d != null) return "died " + d.phrase();
        return i.xref.replace("@", "") + " in " + fileName;
    }

    private static void saveLabels(LibraryStore store, Parsed p, String fileName, Map<String, String> label) throws IOException {
        Path f = labelsFile(store);
        Set<String> have = new HashSet<>(Files.exists(f) ? Files.readAllLines(f, StandardCharsets.UTF_8) : List.of());
        StringBuilder add = new StringBuilder();
        for (Indi i : p.indis().values()) {
            String l = label.get(i.xref);
            if (l == null || l.equals(i.name)) continue;
            String line = fileName.replace('\t', ' ') + "\t" + i.xref + "\t" + i.uid.replace('\t', ' ') + "\t" + l;
            if (have.add(line)) add.append(line).append('\n');
        }
        if (add.isEmpty()) return;
        Files.createDirectories(f.getParent());
        Files.writeString(f, add.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** For each record of this file (by xref), the people an earlier import of it filed its events under. */
    private static Map<String, Set<String>> earlierByRecord(Graph g, List<Finding> all, String source) { return earlierByRecord(all, source, g::nodeIdOf); }

    /** The same, each subject as {@code as} makes of it: a node id, or the name as the claim writes it. */
    private static Map<String, Set<String>> earlierByRecord(List<Finding> all, String source, Function<String, String> as) {
        Map<String, Set<String>> out = new HashMap<>();
        for (Finding f : all) {
            // what an import after it replaced says nothing any more: a joined person it left is not joined now
            if (f.triple() == null || !"gedcom-import".equals(f.writer()) || f.state() == Finding.State.superseded || f.state() == Finding.State.retired) continue;
            for (Finding.Source s : f.sources()) {
                if (!s.locator().equals(source)) continue;
                Matcher m = EDITION.matcher(s.edition());
                if (m.find() && m.group(1).startsWith("@I")) out.computeIfAbsent(m.group(1).substring(0, m.group(1).indexOf(' ')), k -> new HashSet<>()).add(as.apply(f.triple().subject()));
            }
        }
        return out;
    }

    /** The birth date the library holds for each person, by node id, with its range: a born-on date, or the date a born-in claim ends with. */
    static Map<String, FamilyDate> birthDates(Graph g, List<Finding> all) {
        Map<String, FamilyDate> out = new HashMap<>();
        for (Finding f : all) {
            if (f.triple() == null || f.state() == Finding.State.retired || f.state() == Finding.State.superseded || f.state() == Finding.State.disputed) continue;
            String pred = g.predicateOf(f.triple().predicate());
            FamilyDate d = pred.equals("born-on") ? FamilyDate.parse(f.triple().object()) : pred.equals("born-in") ? FamilyChecks.claimDate(f) : null;
            if (d != null) out.putIfAbsent(g.nodeOf(f, true), d);
        }
        return out;
    }

    /**
     * Who in the file may be living, by xref: {@link FamilyLiving}'s rule over the file's own events (a christening or a baptism dates
     * a person as a birth does; a death, a burial or a cremation settles it, dated or not) and its families (a marriage's date, parents).
     */
    private static Map<String, Boolean> living(Map<String, Indi> indis, Map<String, Fam> fams) {
        return FamilyLiving.decide(livingClaims(indis, fams), x -> x);
    }

    /** The file's events and families as the living rule reads them, each person named by their xref. */
    private static List<FamilyLiving.Claim> livingClaims(Map<String, Indi> indis, Map<String, Fam> fams) {
        List<FamilyLiving.Claim> claims = new ArrayList<>();
        for (Indi i : indis.values()) for (Ev e : i.events) {
            boolean death = e.tag.equals("DEAT") || e.tag.equals("BURI") || e.tag.equals("CREM");
            String rel = death ? "died" : e.tag.equals("BIRT") ? "born-in" : e.tag.equals("CHR") || e.tag.equals("BAPM") ? "baptised-in" : e.tag.toLowerCase(Locale.ROOT);
            claims.add(new FamilyLiving.Claim(i.xref, rel, "", e.date));
        }
        for (Fam f : fams.values()) {
            if (!f.husb.isEmpty() && !f.wife.isEmpty()) claims.add(new FamilyLiving.Claim(f.husb, "married-to", f.wife, f.marr.date));
            for (String c : f.chil) {
                // a birth parent's years carry to the child and an adoptive parent's too; a step or foster parent may be any age, so theirs do not
                String[] kinds = belongs(indis.get(c), f, c);
                String[] parents = {f.husb, f.wife};
                for (int k = 0; k < 2; k++) {
                    String parent = parents[k], kind = kinds[k].strip().toLowerCase(Locale.ROOT);
                    if (parent.isEmpty() || kind.equals(NOT_SAID)) continue;
                    if (kind.startsWith("adopt")) claims.add(new FamilyLiving.Claim(c, "adopted-by", parent, ""));
                    else if (!kind.startsWith("foster") && !kind.startsWith("step")) claims.add(new FamilyLiving.Claim(parent, "parent-of", c, ""));
                }
            }
        }
        return claims;
    }

    /**
     * A GEDCOM name, "Given /Family/", as the person is written: a name in Chinese characters, kana or hangul is written family name
     * first with no space (髙橋正一), whatever order the file's form imposes; any other name keeps the file's order.
     */
    static String personName(String gedcomName) {
        Matcher m = Pattern.compile("^(.*?)/([^/]*)/(.*)$").matcher(gedcomName == null ? "" : gedcomName.strip());
        if (!m.find()) return gedcomName == null ? "" : gedcomName.replace("/", "").replaceAll("\\s+", " ").strip();
        String given = m.group(1).strip(), family = m.group(2).strip(), suffix = m.group(3).strip();
        boolean eastAsian = !family.isEmpty() && family.codePoints().allMatch(c -> (c >= 0x3040 && c <= 0x30ff) || (c >= 0x3400 && c <= 0x9fff) || (c >= 0xac00 && c <= 0xd7af) || (c >= 0xf900 && c <= 0xfaff));
        if (eastAsian) return (family + given.replace(" ", "") + (suffix.isEmpty() ? "" : " " + suffix)).strip();
        return (given + " " + family + (suffix.isEmpty() ? "" : " " + suffix)).replaceAll("\\s+", " ").strip();
    }

    /** The words before the date a source wrote its entry on, in a source's edition: what {@link Evidence#writtenLater} reads back. */
    static final String ENTERED = "the source wrote this down on ";

    /** "; cited there: <the source's title> <page>" for an event's citation in the file. */
    private static String citation(String cite, Map<String, String> sourceTitles) {
        String c = cite.strip(), rating = "", entered = "", page = "";
        Matcher em = Pattern.compile("\u0002(.*?)\u0003").matcher(c);
        if (em.find()) { entered = "; " + ENTERED + em.group(1).strip(); c = (c.substring(0, em.start()) + c.substring(em.end())).replaceAll("\\s+", " ").strip(); }
        Matcher pm = Pattern.compile("\u0004(.*?)\u0005").matcher(c);
        if (pm.find()) { page = pm.group(1).strip(); c = (c.substring(0, pm.start()) + c.substring(pm.end())).replaceAll("\\s+", " ").strip(); }
        if (c.contains("\u0001")) { rating = c.substring(c.indexOf('\u0001') + 1).strip(); c = c.substring(0, c.indexOf('\u0001')).strip(); }
        if (c.isBlank()) return (rating.isEmpty() ? "" : "; " + rating) + entered;
        String ref = c.contains(" ") ? c.substring(0, c.indexOf(' ')) : c;
        String title = sourceTitles.get(ref);
        String rest = c.contains(" ") ? c.substring(c.indexOf(' ') + 1).strip() : "";
        if (title == null && !ref.startsWith("@")) { title = c; rest = ""; }
        return "; cited there: " + Acquisitions.compress((title != null ? title : "a source of the file") + (rest.isEmpty() ? "" : ", " + rest), 200) + (rating.isEmpty() ? "" : "; " + rating) + entered + unit(title != null ? title : ref, page);
    }

    /** " {page: <the source's title> ¦ <the page>}" for a citation that names a page; "" otherwise. Kept at the end of the claim's source edition. */
    private static String unit(String title, String page) {
        if (page.isBlank() || title == null || title.isBlank()) return "";
        return " {page: " + title.replaceAll("[|¦{}]", "/") + " ¦ " + page.replaceAll("[|¦{}]", "/") + "}";
    }

    private static final Pattern UNIT = Pattern.compile("\\{page: (.+?) ¦ (.+?)\\}");

    /** The source and the page a claim's source cites, {title, page}, when a tree file gave both; else null. */
    public static String[] unitOf(Finding.Source s) {
        Matcher m = UNIT.matcher(s == null ? "" : s.edition());
        return m.find() ? new String[]{m.group(1).strip(), m.group(2).strip()} : null;
    }

    /** One page of one source, however the two files write it: "p. 12" and "page 12" are one page. */
    public static String unitKey(String[] unit) {
        return FamilyQuestions.plain(unit[0]).replaceAll("[^\\p{L}\\p{N}]+", " ").strip() + " ¦ "
                + FamilyQuestions.plain(unit[1]).replaceAll("(?<![\\p{L}])(pages?|pp?|pg|folio|fol|f)(?![\\p{L}])\\.?", "").replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    /**
     * Files the claims of one import: each edge once, and a newer copy of the file correcting what an earlier copy said of an event a person
     * has once. The correcting is done at the end, event by event: an earlier draft is replaced only when this copy of the file no longer
     * says what it says, in the words it has now or in the words an older version of the program wrote. What this import itself filed is
     * never an earlier draft, so two births the file gives one person are both kept.
     */
    private static final class Writer {
        final LibraryStore store; final String source, headDate;
        final List<Finding> all;
        int written, superseded, backed, moved;
        /** Names, memberships and a family's own facts from an earlier copy of the file that this copy says otherwise. */
        int renamed;
        /** By a family's children ("@F1@ CHIL"): the drafts an earlier copy filed for them, and those this copy says again. */
        final Map<String, List<Finding>> earlierKin = new LinkedHashMap<>();
        final Set<String> kinAgain = new HashSet<>();
        /** The claims this import wrote. */
        final Set<String> mine = new HashSet<>();
        /** By the key of an event a person has once: what an earlier copy of the file filed, which of those this copy says again, and what this copy says. */
        final Map<String, List<Finding>> earlierOf = new LinkedHashMap<>();
        final Map<String, Set<String>> saidAgain = new HashMap<>();
        final Map<String, List<String>> saysNow = new LinkedHashMap<>();
        final Map<String, List<Finding>> filedNow = new LinkedHashMap<>();

        Writer(LibraryStore store, String source, String headDate) {
            this.store = store; this.source = source; this.headDate = headDate;
            this.all = new ArrayList<>(store.scanFindings().findings());
        }

        String why() { return "the family file the person shelved" + (headDate.isBlank() ? "" : ", dated " + headDate); }

        String edge(String subject, String predicate, String object, String date, List<String> editions, String notes, String key) throws IOException {
            return edge(subject, predicate, object, date, editions, notes, key, Finding.Confidence.medium);
        }

        String edge(String subject, String predicate, String object, String date, List<String> editions, String notes, String key, Finding.Confidence confidence) throws IOException {
            return edge(subject, predicate, object, date, editions, notes, key, confidence, Map.of());
        }

        /**
         * One claim, and the id of the claim that says it now: the one written, the one held that the file joins, or the earlier copy's claim
         * this copy says again; null when nothing says it. {@code detail}: the claim's reading ({@link FamilyDetail}) beside its triple: a
         * name's kind and years, a membership's how and role, an adoption's kind. A held claim that reads the fact otherwise is not joined: a
         * changed reading is a claim of its own.
         */
        String edge(String subject, String predicate, String object, String date, List<String> editions, String notes, String key, Finding.Confidence confidence,
                    Map<String, String> detail) throws IOException {
            Map<String, String> reading = detail == null ? Map.of() : detail;
            Finding.Triple t = new Finding.Triple(subject, predicate, object);
            String claim = FamilyAccount.sentence(subject, predicate, object, reading) + (date.isBlank() ? "" : " (" + date + ")") + ".";
            if (key != null) {
                List<Finding> earlier = new ArrayList<>();
                for (Finding f : all) {
                    if (mine.contains(f.id()) || !"gedcom-import".equals(f.writer()) || f.state() == Finding.State.retired || f.state() == Finding.State.superseded) continue;
                    if (f.sources().stream().anyMatch(s -> s.locator().equals(source) && EDITION.matcher(s.edition()).find() && key.equals(keyOf(s.edition())))) earlier.add(f);
                }
                earlierOf.putIfAbsent(key, earlier);
                saysNow.computeIfAbsent(key, k -> new ArrayList<>()).add(claim);
                // what an earlier copy said, in its words or as the same fact with the same date in other words ("born in Leeds (1860)" and
                // "was born in Leeds (1860)"): accepted, disputed or a draft, it is not filed again
                List<Finding> again = earlierOf.get(key).stream().filter(f -> {
                    String first = f.body().lines().findFirst().orElse("").strip();
                    return (first.equals(claim) || (sameFact(f.triple(), t) && dateOf(f).equalsIgnoreCase(date.strip()))) && sameReading(f, reading);
                }).toList();
                if (!again.isEmpty()) { for (Finding f : again) saidAgain.computeIfAbsent(key, k -> new HashSet<>()).add(f.id()); return again.get(0).id(); }
            }
            // a family's children: what an earlier copy filed for them and this copy no longer says is corrected at the end
            Matcher fam = FAMILY_KIN.matcher(editions.isEmpty() ? "" : editions.get(0));
            String kinKey = key == null && fam.find() ? fam.group(1) : null;
            if (kinKey != null) earlierKin.computeIfAbsent(kinKey, k -> all.stream().filter(f -> !mine.contains(f.id()) && "gedcom-import".equals(f.writer()) && f.state() == Finding.State.draft
                    && f.sources().stream().anyMatch(s -> s.locator().equals(source) && s.edition().startsWith("GEDCOM " + k))).toList());
            List<Finding.Source> sources = new ArrayList<>();
            for (String e : editions) sources.add(new Finding.Source(source, e, why()));
            // the fact held already, from another source or from this file under another date (two births in York the file gives, 1851 and
            // 1852, are two claims); an event this file said something else about is corrected at the end instead. The same place with a
            // year the held claim does not allow is another claim, so the check shows the two years. A claim this very record joined on an
            // earlier read is held, whoever wrote it and however exactly it gives the date ("1922" in the notes, "12 MAR 1922" in the file)
            List<Finding> same = key != null && !earlierOf.get(key).isEmpty() ? List.of()
                    : all.stream().filter(f -> sameFact(f.triple(), t) && f.state() != Finding.State.superseded
                            && ((!mine.contains(f.id()) && f.sources().stream().anyMatch(x -> x.locator().equals(source) && editions.contains(x.edition())))
                                || !(f.sources().stream().anyMatch(x -> x.locator().equals(source)) && !dateOf(f).equalsIgnoreCase(date.strip())))
                            && (predicate.endsWith("-on") || FamilyAccount.datesFit(f, date)) && readingFits(f, reading)).toList();
            if (kinKey != null) for (Finding f : same) kinAgain.add(f.id());
            if (!same.isEmpty()) {
                // the fact is held from another source: the file joins it as a further source, also of a claim the library disputed by itself so
                // that later sources can settle it, and a claim the person disputed or retired is only told that the file says it again
                Finding held = FamilyAccount.told(same), now = held == null ? null : Evidence.toldAgain(store, held, sources, "gedcom-import", "");
                if (now != null) { all.set(all.indexOf(held), now); if (now.state() == Finding.State.draft || now.state() == Finding.State.accepted || now.setAsideBy() == Finding.SetAside.library) backed++; }
                return now != null ? now.id() : held != null ? held.id() : null;
            }
            String id = store.nextFindingId(subject + " " + predicate + " " + object);
            String body = claim + "\n" + (notes == null || notes.isBlank() ? "" : "\nThe file's note on " + subject + ": " + Acquisitions.compress(notes, 600) + "\n");
            Finding f = new Finding(id, Acquisitions.compress(claim, 80), List.of(), Finding.State.draft, Finding.ClaimType.extraction,
                    confidence, "gedcom-import", Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "",
                    sources, List.of(), null, body, t, reading.isEmpty() ? List.of() : List.of(FamilyDetail.note(reading, "gedcom-import")));
            store.write(f);
            all.add(f);
            mine.add(id);
            written++;
            if (key != null) filedNow.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
            return id;
        }

        /** The readings that decide what a claim says: a name's kind and years, how somebody came into a family and left it, their role, an adoption's kind. */
        private static final List<String> SAYS = List.of("kind", "from", "to", "how", "left", "role", "took", "ended");

        /** Words a reading uses for "the source does not say", which say nothing a held claim could disagree with. */
        private static boolean silent(String v) { return v == null || v.isBlank() || v.equals("unknown") || v.equals("unstated"); }

        /** Whether a held claim reads a fact as this reading does, so the file may join it: nothing the reading says is said otherwise, or left unsaid, by the claim. */
        private static boolean readingFits(Finding held, Map<String, String> reading) {
            if (reading.isEmpty()) return true;
            Map<String, String> was = FamilyDetail.of(held);
            for (String k : SAYS) {
                String now = reading.get(k);
                if (silent(now)) continue;
                String then = was.get(k);
                if (silent(then)) return false;
                if (k.equals("from") || k.equals("to")) {
                    FamilyDate a = FamilyDate.parse(now), b = FamilyDate.parse(then);
                    if (a != null && b != null ? a.year() != b.year() : !now.equalsIgnoreCase(then)) return false;
                } else if (!now.equalsIgnoreCase(then)) return false;
            }
            return true;
        }

        /** Whether an earlier copy's claim carries this very reading, so the file says it again: the same values, key by key, the empty ones aside. */
        private static boolean sameReading(Finding f, Map<String, String> reading) {
            Map<String, String> was = FamilyDetail.of(f);
            if (reading.isEmpty()) return was.isEmpty();
            Set<String> keys = new HashSet<>(reading.keySet());
            keys.addAll(was.keySet());
            for (String k : keys) if (!reading.getOrDefault(k, "").strip().equals(was.getOrDefault(k, "").strip())) return false;
            return true;
        }

        private static final Pattern BRACKET = Pattern.compile("\\(([^()]*)\\)[.。]?\\s*$");
        private static final Pattern FAMILY_KIN = Pattern.compile("^GEDCOM (@F[^@]*@ CHIL)");

        /** The date a claim's first line ends with in brackets, as the import writes it, the names taken out first ("Tom Ellis (born 1990)" is a name); "" when none. */
        private static String dateOf(Finding f) {
            String first = f.body().lines().findFirst().orElse("").strip();
            if (f.triple() != null) for (String name : new String[]{f.triple().subject(), f.triple().object()}) if (name != null && !name.isBlank()) first = first.replace(name, " ");
            Matcher m = BRACKET.matcher(first);
            return m.find() ? m.group(1).strip() : "";
        }

        /** One fact: the same subject and relation and the same value, however the sentence around it is worded. */
        private static boolean sameFact(Finding.Triple a, Finding.Triple b) {
            return a != null && b != null && a.sameKey(b) && Finding.Triple.canon(a.object()).equals(Finding.Triple.canon(b.object()));
        }

        /**
         * The correcting, once every event is read: for each event a person has once, the earlier copy's drafts this copy does not say
         * again are replaced by what this copy says. A draft from a copy of the file dated after this one is left as it is.
         */
        void finish() throws IOException {
            for (var e : earlierOf.entrySet()) {
                String key = e.getKey();
                Set<String> again = saidAgain.getOrDefault(key, Set.of());
                List<Finding> drafts = new ArrayList<>();
                for (Finding old : e.getValue()) {
                    Finding cur = all.stream().filter(x -> x.id().equals(old.id())).findFirst().orElse(old);
                    if (cur.state() == Finding.State.draft && !again.contains(cur.id()) && !older(cur)) drafts.add(cur);
                }
                if (drafts.isEmpty()) continue;
                List<Finding> fresh = filedNow.getOrDefault(key, List.of());
                String instead = String.join(" ", saysNow.getOrDefault(key, List.of()));
                List<String> ids = drafts.stream().map(Finding::id).toList();
                for (Finding old : drafts) {
                    // the same event, now filed under a person the file names apart (a father and a son an older version took for one man)
                    boolean movedHere = fresh.stream().anyMatch(f -> old.triple() != null && f.triple() != null && old.triple().predicate().equals(f.triple().predicate())
                            && Finding.Triple.canon(old.triple().object()).equals(Finding.Triple.canon(f.triple().object())) && dateOf(old).equalsIgnoreCase(dateOf(f))
                            && !Vocabulary.norm(old.triple().subject()).equals(Vocabulary.norm(f.triple().subject())));
                    List<Finding.Note> notesNow = new ArrayList<>(old.notes());
                    notesNow.add(new Finding.Note("supersedes", "gedcom-import", LocalDate.now().toString(), "a newer copy of the family file says instead: " + instead
                            + (fresh.isEmpty() ? "" : " (" + String.join(", ", fresh.stream().map(Finding::id).toList()) + ")")));
                    Finding now = new Finding(old.id(), old.title(), old.subjects(), Finding.State.superseded, old.claimType(), old.confidence(), old.writer(), old.recordedAt(), old.validAsOf(),
                            old.volatility(), old.reviewBy(), old.sources(), old.supersedes(), old.review(), old.body(), old.triple(), notesNow);
                    store.write(now);
                    all.set(all.indexOf(all.stream().filter(x -> x.id().equals(old.id())).findFirst().orElseThrow()), now);
                    if (movedHere) moved++; else if (ofNames(key)) renamed++; else superseded++;
                }
                for (Finding f : fresh) {
                    Finding now = new Finding(f.id(), f.title(), f.subjects(), f.state(), f.claimType(), f.confidence(), f.writer(), f.recordedAt(), f.validAsOf(), f.volatility(), f.reviewBy(),
                            f.sources(), ids, f.review(), f.body() + "\nA newer copy of the family file says this in place of what it said before (" + String.join(", ", ids) + ").\n", f.triple(), f.notes());
                    store.write(now);
                    all.set(all.indexOf(f), now);
                }
            }
            // a family's children as an earlier copy filed them and this copy no longer says: an older version that took two people of one
            // name for one filed "John Ellis is a parent of John Ellis", and this copy files the two apart
            for (var e : earlierKin.entrySet()) {
                for (Finding old : e.getValue()) {
                    Finding cur = all.stream().filter(x -> x.id().equals(old.id())).findFirst().orElse(old);
                    if (cur.state() != Finding.State.draft || kinAgain.contains(cur.id()) || older(cur)) continue;
                    List<Finding.Note> notesNow = new ArrayList<>(cur.notes());
                    notesNow.add(new Finding.Note("supersedes", "gedcom-import", LocalDate.now().toString(), "a newer copy of the family file no longer says this of the family " + e.getKey().replace(" CHIL", "")));
                    Finding now = new Finding(cur.id(), cur.title(), cur.subjects(), Finding.State.superseded, cur.claimType(), cur.confidence(), cur.writer(), cur.recordedAt(), cur.validAsOf(),
                            cur.volatility(), cur.reviewBy(), cur.sources(), cur.supersedes(), cur.review(), cur.body(), cur.triple(), notesNow);
                    store.write(now);
                    all.set(all.indexOf(cur), now);
                    superseded++;
                }
            }
        }

        /** The earlier draft came from a copy of the file dated after this one: this copy does not correct it. */
        /** Whether the key is of a person's names, their memberships, or a family's seat or branch, rather than of an event. */
        private static boolean ofNames(String key) { return key.matches("\\S+ (NAME|_MEMBER|_SEAT|_BRANCH)"); }

        private boolean older(Finding f) {
            LocalDate mine = headDay(headDate);
            if (mine == null) return false;
            for (Finding.Source s : f.sources()) {
                Matcher m = Pattern.compile(", dated (.+)$").matcher(s.whyItMatters());
                if (m.find()) { LocalDate theirs = headDay(m.group(1)); if (theirs != null && mine.isBefore(theirs)) return true; }
            }
            return false;
        }
    }

    private static String keyOf(String edition) { Matcher m = EDITION.matcher(edition); return m.find() ? m.group(1) : ""; }

    private static final DateTimeFormatter HEAD_DAY = new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMM yyyy").toFormatter(Locale.ENGLISH);

    private static LocalDate headDay(String written) {
        try { return LocalDate.parse(written.strip(), HEAD_DAY); } catch (Exception e) { return null; }
    }

    // ---- before importing ----

    /**
     * What an import of the file would do, and nothing written: how many people and families, how many may still be living by the file's
     * own dates, who would be kept apart, and each person in the file who may be somebody already in the library, with the evidence for
     * and against.
     */
    public static String dryRun(LibraryStore store, Path file) throws IOException {
        Parsed p = parse(file);
        String fileName = file.getFileName().toString();
        String source = "file://" + file.toAbsolutePath().normalize();
        Graph g = FamilyPeople.view(store);
        List<Finding> all = store.scanFindings().findings();
        List<String> apart = new ArrayList<>();
        Map<String, String> label = labels(store, p, fileName, source, g, all, apart);
        Map<String, Boolean> living = living(p.indis(), p.fams());
        List<String> livingNames = p.indis().values().stream().filter(i -> !i.name.isBlank() && living.getOrDefault(i.xref, true)).map(i -> label.getOrDefault(i.xref, i.name)).toList();
        long alive = livingNames.size();
        StringBuilder b = new StringBuilder("This is what importing " + fileName + " would do. Nothing has been changed yet.\n\n");
        b.append("The file holds ").append(p.indis().size()).append(p.indis().size() == 1 ? " person" : " people").append(" and ").append(p.fams().size()).append(p.fams().size() == 1 ? " family" : " families")
         .append(". Its letters are read as ").append(p.charset()).append(".\n");
        long named = p.indis().values().stream().filter(i -> !i.name.isBlank() && i.names.size() > 1).count();
        if (named > 0) b.append(named).append(named == 1 ? " person in the file has" : " people in the file have").append(" more than one name, such as a birth name and a married name. Each name is kept with how it came and its years, as the file gives them.\n");
        if (!p.houses().isEmpty()) b.append("The file describes ").append(p.houses().size()).append(p.houses().size() == 1 ? " family" : " families")
                .append(" as a house or a line, with who belonged to ").append(p.houses().size() == 1 ? "it" : "them").append(". Each becomes a family entry in your library.\n");
        if (alive > 0) b.append(alive).append(alive == 1 ? " person" : " people").append(" in the file may still be living, by its dates: a search asks about their work and public life, not about their death: ")
         .append(String.join(", ", livingNames.stream().limit(30).toList())).append(alive > 30 ? " and " + (alive - 30) + " more" : "").append(".\n");
        if (!apart.isEmpty()) { b.append("\nPEOPLE WHO SHARE A NAME\n"); for (String a : apart) b.append("  ").append(a).append('\n'); }
        Map<String, String> known = FamilyNames.known(g);
        Map<String, FamilyDate> heldBorn = birthDates(g, all);
        Map<String, Set<String>> earlier = earlierByRecord(g, all, source);
        List<String> joined = new ArrayList<>(), maybe = new ArrayList<>();
        int again = 0;
        for (Indi i : p.indis().values()) {
            if (i.name.isBlank() || !label.getOrDefault(i.xref, i.name).equals(i.name)) continue;
            if (earlier.getOrDefault(i.xref, Set.of()).contains(g.nodeIdOf(i.name))) { again++; continue; }
            Graph.Node exact = g.node(g.nodeIdOf(i.name));
            String heldLabel = exact != null && "person".equals(exact.kind()) ? exact.label() : null;
            boolean sameWriting = heldLabel != null;
            if (heldLabel == null) for (String k : FamilyNames.keys(i.name)) { heldLabel = known.get(k); if (heldLabel != null) break; }
            if (heldLabel == null) continue;
            String heldId = g.nodeIdOf(heldLabel);
            FamilyDate mine = i.date("BIRT"), theirs = heldBorn.get(heldId);
            // a name written another way whose birth is years from the one in the library is somebody else: not a person to join
            if (!sameWriting && mine != null && theirs != null && FamilyDate.apart(mine, theirs, 2)) continue;
            List<String> shared = sharedRelatives(p, i, label, g, known, heldId);
            String evidence = (mine != null && theirs != null ? " Both were born around the same time (" + mine.phrase() + " and " + theirs.phrase() + ")." : " No birth year sets them apart.")
                    + (shared.isEmpty() ? " No relative of one is known as a relative of the other." : " " + shared.size() + (shared.size() == 1 ? " relative is" : " relatives are") + " the same for both: " + String.join(", ", shared) + ".");
            if (sameWriting) joined.add(i.name + " in the file will be joined with " + heldLabel + " in your library." + evidence);
            else maybe.add(i.name + " in the file may be " + heldLabel + " in your library, written another way. The import keeps them as two people." + evidence);
        }
        if (again > 0) b.append(again).append(again == 1 ? " person was" : " people were").append(" read from this file before. The import finds them again and adds only what is new or changed.\n");
        if (!joined.isEmpty()) {
            b.append("\nPEOPLE ALREADY IN YOUR LIBRARY UNDER THE SAME NAME (").append(joined.size()).append(")\nThe import joins these with the person already in your library. If one of them is somebody else, "
                    + "split them afterwards with researchzosho genealogy split (researchzosho genealogy check shows how).\n");
            for (String j : joined) b.append("  ").append(j).append('\n');
        }
        if (!maybe.isEmpty()) {
            b.append("\nPEOPLE WHO MAY BE IN YOUR LIBRARY UNDER ANOTHER SPELLING (").append(maybe.size()).append(")\nIf they are one person, researchzosho genealogy tidy joins people whose name is written two ways, after asking you.\n");
            for (String m : maybe) b.append("  ").append(m).append('\n');
        }
        List<String> problems = new ArrayList<>(p.problems());
        for (Map.Entry<String, Integer> n : p.notRead().entrySet()) problems.add(notRead(n.getKey(), n.getValue()));
        if (!problems.isEmpty()) { b.append("\nWHAT THE IMPORT WOULD LEAVE OUT OR CHANGE\n"); for (String x : problems) b.append("  ").append(x).append('\n'); }
        b.append("\nTo import the file, give the same command without --dry.\n");
        return b.toString();
    }

    /** The relatives of a person in the file who are also relatives of a person in the library, by name. */
    private static List<String> sharedRelatives(Parsed p, Indi i, Map<String, String> label, Graph g, Map<String, String> known, String heldId) {
        Set<String> mine = new LinkedHashSet<>();
        for (Fam f : p.fams().values()) {
            boolean parent = f.husb.equals(i.xref) || f.wife.equals(i.xref), child = f.chil.contains(i.xref);
            if (parent) { mine.add(f.husb); mine.add(f.wife); mine.addAll(f.chil); }
            if (child) { mine.add(f.husb); mine.add(f.wife); }
        }
        mine.remove(i.xref); mine.remove("");
        Set<String> theirs = new HashSet<>();
        for (Graph.Edge e : g.edges()) {
            if (!FamilyAccount.personToPerson(e.predicate())) continue;
            if (e.from().equals(heldId)) theirs.add(e.to());
            if (e.to().equals(heldId)) theirs.add(e.from());
        }
        List<String> out = new ArrayList<>();
        for (String x : mine) {
            String l = label.get(x);
            if (l == null) continue;
            String id = g.nodeIdOf(l);
            if (!theirs.contains(id)) for (String k : FamilyNames.keys(l)) { String kl = known.get(k); if (kl != null) { id = g.nodeIdOf(kl); break; } }
            if (theirs.contains(id)) out.add(l);
        }
        return out;
    }

    // ---- export ----

    private static final Set<String> KIN = Set.of("parent-of", "child-of", "married-to", "adopted-by", "foster-child-of", "step-parent-of", "sibling-of", "parent-in-law-of");

    /**
     * The family around {@code focus} as GEDCOM 5.5.1: the people reachable through family relations, each claim with its sources, a
     * draft marked as one, and a disputed claim left out. Everyone is written in full, the living too.
     */
    public static String export(LibraryStore store, String focus) throws IOException {
        Graph g = FamilyPeople.view(store);
        String head = "0 HEAD\n1 SOUR ResearchZosho\n1 GEDC\n2 VERS 5.5.1\n1 CHAR UTF-8\n";
        StringBuilder sb = new StringBuilder();
        String start = g.nodeIdOf(focus);
        if (g.node(start) == null) { Finding f = store.finding(focus); if (f != null && f.triple() != null) start = g.nodeOf(f, true); }
        Graph.Node first = g.node(start);
        if (first == null || !"person".equals(first.kind())) return head + "0 TRLR\n";
        FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
        boolean own = false;                                     // whether the file uses the library's own tags, which the header then explains
        Map<String, String> houseOf = new LinkedHashMap<>();     // a family's node → its _HOUSE record
        List<Graph.Edge> edges = new ArrayList<>();
        for (Graph.Edge e : g.edges()) if (!e.disputed() && !"superseded".equals(e.state())) edges.add(e);
        Map<String, List<Graph.Edge>> byNode = new HashMap<>();
        for (Graph.Edge e : edges) { byNode.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e); byNode.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e); }
        // the family: people reachable through family relations only, not through a town they shared
        Map<String, String> xref = new LinkedHashMap<>();
        ArrayDeque<String[]> q = new ArrayDeque<>();
        q.add(new String[]{start, "0"});
        xref.put(start, "@I1@");
        while (!q.isEmpty() && xref.size() < 5000) {
            String[] cur = q.poll();
            if (Integer.parseInt(cur[1]) >= 12) continue;
            for (Graph.Edge e : byNode.getOrDefault(cur[0], List.of())) {
                if (!KIN.contains(e.predicate())) continue;
                String other = e.from().equals(cur[0]) ? e.to() : e.from();
                Graph.Node on = g.node(other);
                if (on == null || !"person".equals(on.kind()) || xref.containsKey(other) || FamilyQuestions.unknown(on.label())) continue;
                xref.put(other, "@I" + (xref.size() + 1) + "@");
                q.add(new String[]{other, String.valueOf(Integer.parseInt(cur[1]) + 1)});
            }
        }
        // families: one per couple, children attached by how they belong to each parent: born to them, adopted, fostered, or a step-child
        Map<String, Set<String>> children = new LinkedHashMap<>();
        Map<String, String> belongs = new HashMap<>();   // "parent|child" → birth, adopted, foster, step
        Map<String, Graph.Edge> couples = new LinkedHashMap<>();
        for (Graph.Edge e : edges) {
            if (!xref.containsKey(e.from()) || !xref.containsKey(e.to()) || e.from().equals(e.to())) continue;
            if (e.predicate().equals("married-to")) { String k = e.from().compareTo(e.to()) < 0 ? e.from() + "|" + e.to() : e.to() + "|" + e.from(); couples.putIfAbsent(k, e); }
            String[] pc = switch (e.predicate()) {
                case "parent-of" -> new String[]{e.from(), e.to(), "birth"};
                case "child-of" -> new String[]{e.to(), e.from(), "birth"};
                case "adopted-by" -> new String[]{e.to(), e.from(), "adopted"};
                case "foster-child-of" -> new String[]{e.to(), e.from(), "foster"};
                case "step-parent-of" -> new String[]{e.from(), e.to(), "step"};
                default -> null;
            };
            if (pc == null) continue;
            children.computeIfAbsent(pc[0], k -> new LinkedHashSet<>()).add(pc[1]);
            belongs.merge(pc[0] + "|" + pc[1], pc[2], (a, b) -> a.equals("birth") ? a : b);
        }
        // the husband first, where the claims say who is the man; otherwise the order the ids come in
        Map<String, String> sexOf = new HashMap<>();
        for (Graph.Edge e : edges) if (e.predicate().equals("sex") && xref.containsKey(e.from())) { String w = label(g, e.to()); if (w.equals("male") || w.equals("female")) sexOf.putIfAbsent(e.from(), w); }
        Map<String, String> famOf = new LinkedHashMap<>();
        // a couple's children are the ones both of them are parents of (by birth, adoption, fostering or as a step-parent): a husband's
        // child by another wife is not hers, and a program that reads the file takes both of a family's parents for the child's
        Map<String, Set<String>> kidsOf = new LinkedHashMap<>();
        for (String c : couples.keySet()) {
            famOf.put(c, "@F" + (famOf.size() + 1) + "@");
            String[] p = c.split("\\|");
            Set<String> both = new LinkedHashSet<>(children.getOrDefault(p[0], Set.of()));
            both.retainAll(children.getOrDefault(p[1], Set.of()));
            kidsOf.put(c, both);
        }
        // a child with no couple of its parents has a family of the one parent, or the link would be lost: "p|", beside any marriages
        for (var ch : children.entrySet()) {
            if (!xref.containsKey(ch.getKey())) continue;
            for (String kid : ch.getValue()) {
                if (!xref.containsKey(kid)) continue;
                boolean withSpouse = false;
                for (var k : kidsOf.entrySet()) if (!k.getKey().endsWith("|") && List.of(k.getKey().split("\\|")).contains(ch.getKey()) && k.getValue().contains(kid)) withSpouse = true;
                if (withSpouse) continue;
                String one = ch.getKey() + "|";
                if (!famOf.containsKey(one)) famOf.put(one, "@F" + (famOf.size() + 1) + "@");
                kidsOf.computeIfAbsent(one, k -> new LinkedHashSet<>()).add(kid);
            }
        }
        Map<String, String> sourceOf = new LinkedHashMap<>();   // where a claim came from (a site, or a file's name) → its SOUR record
        List<Frontier.Line> open = Frontier.read(store).stream().filter(l -> l.open() && Set.of("report", "asked", "person").contains(l.type())).toList();
        for (Map.Entry<String, String> x : xref.entrySet()) {
            Graph.Node node = g.node(x.getKey());
            sb.append("0 ").append(x.getValue()).append(" INDI\n");
            own |= names(sb, idx, node, sourceOf);
            if (sexOf.containsKey(node.id())) sb.append("1 SEX ").append(sexOf.get(node.id()).equals("male") ? "M" : "F").append('\n');
            for (Graph.Edge e : byNode.getOrDefault(node.id(), List.of())) {
                if (!e.from().equals(node.id())) continue;
                String tag = switch (e.predicate()) {
                    case "born-in", "born-on" -> "BIRT"; case "died-in", "died-on" -> "DEAT"; case "baptised-in", "baptised-on" -> "BAPM"; case "buried-in" -> "BURI";
                    case "lived-in" -> "RESI"; case "migrated-to" -> "IMMI"; case "occupation" -> "OCCU"; default -> null; };
                if (tag == null) continue;
                Finding f = store.finding(e.findingId());
                String what = label(g, e.to());
                if (tag.equals("OCCU")) {
                    // the year a job is known in dates the person, for whoever reads the file as much as for this library
                    sb.append("1 OCCU ").append(what).append('\n');
                    String when = f == null ? "" : FamilyAccount.lastBracket(firstWithoutNames(f));
                    if (!when.isBlank()) sb.append("2 DATE ").append(when).append('\n');
                } else {
                    sb.append("1 ").append(tag).append('\n');
                    if (e.predicate().endsWith("-on")) sb.append("2 DATE ").append(what).append('\n');
                    else {
                        if (f != null) { Matcher m = Pattern.compile("\\(([^)]+)\\)\\.\\s*$").matcher(f.body().lines().findFirst().orElse("").strip()); if (m.find()) sb.append("2 DATE ").append(m.group(1)).append('\n'); }
                        sb.append("2 PLAC ").append(what).append('\n');
                    }
                }
                cite(sb, f, sourceOf);
            }
            // an open question goes under the person it is about, the name it starts with, and not under every relative it names as a lead
            String name = Vocabulary.norm(bare(node.label())), full = Vocabulary.norm(node.label());
            for (Frontier.Line l : open) {
                String text = Frontier.strip(l.text()), about = Vocabulary.norm(text.replaceFirst("\\s*[(:（].*$", ""));
                if (name.length() < 3 || !(about.equals(name) || about.equals(full))) continue;
                sb.append("1 _TODO ").append(todoText(text)).append('\n');
            }
            // the file's own dated events without a place come back as the events they were
            for (Graph.Edge e : byNode.getOrDefault(node.id(), List.of())) {
                if (!e.from().equals(node.id()) || !e.predicate().equals("life-event")) continue;
                String what = label(g, e.to()), tag = null;
                for (var u : UNPLACED.entrySet()) if (u.getValue().equals(what)) tag = u.getKey();
                if (tag == null) continue;
                Finding f = store.finding(e.findingId());
                String when = f == null ? "" : FamilyAccount.lastBracket(firstWithoutNames(f));
                sb.append("1 ").append(tag).append('\n');
                if (!when.isBlank()) sb.append("2 DATE ").append(when).append('\n');
                cite(sb, f, sourceOf);
            }
            for (Map.Entry<String, String> f : famOf.entrySet()) if (f.getKey().startsWith(node.id() + "|") || f.getKey().endsWith("|" + node.id())) sb.append("1 FAMS ").append(f.getValue()).append('\n');
            Map<String, String> famc = new LinkedHashMap<>();   // the family → how the child belongs to both of its parents, when that is the same for both
            for (Map.Entry<String, String> f : famOf.entrySet()) if (kidsOf.getOrDefault(f.getKey(), Set.of()).contains(node.id())) {
                String[] c = f.getKey().split("\\|", -1);
                String a = belongs.getOrDefault(c[0] + "|" + node.id(), ""), b = belongs.getOrDefault(c[1] + "|" + node.id(), "");
                famc.putIfAbsent(f.getValue(), a.equals(b) && (a.equals("adopted") || a.equals("foster")) ? a : "");
            }
            for (var f : famc.entrySet()) sb.append("1 FAMC ").append(f.getKey()).append('\n').append(f.getValue().isEmpty() ? "" : "2 PEDI " + f.getValue() + "\n");
            // an adoption as the event it was: when, what kind, and which parent of the family adopted
            for (Map.Entry<String, String> f : famOf.entrySet()) {
                if (!kidsOf.getOrDefault(f.getKey(), Set.of()).contains(node.id())) continue;
                String[] hw = spouses(f.getKey(), sexOf);
                List<Graph.Edge> by = byNode.getOrDefault(node.id(), List.of()).stream().filter(e -> e.from().equals(node.id()) && e.predicate().equals("adopted-by")
                        && (e.to().equals(hw[0]) || e.to().equals(hw[1]))).toList();
                if (by.isEmpty()) continue;
                boolean husband = by.stream().anyMatch(e -> e.to().equals(hw[0])), wife = by.stream().anyMatch(e -> e.to().equals(hw[1]));
                adoption(sb, store, by, f.getValue(), husband && wife ? "BOTH" : husband ? "HUSB" : "WIFE", sourceOf);
            }
            // the families the person belonged to: how they came in and left, the years, their role
            for (FamilyHouses.Membership m : FamilyHouses.families(g, node.id())) {
                String hx = houseOf.computeIfAbsent(m.family(), k -> "@H" + (houseOf.size() + 1) + "@");
                sb.append("1 _MEMBER ").append(hx).append('\n');
                // an end worked out from the next family is no year a source gave: the file leaves it out, as the library would read it back as given
                String period = period(m.from(), m.toWorkedOut() ? null : m.to());
                if (!period.isEmpty()) sb.append("2 DATE ").append(period).append('\n');
                if (!m.how().isBlank()) sb.append("2 _HOW ").append(m.how()).append('\n');
                if (!m.left().isBlank()) sb.append("2 _LEFT ").append(m.left()).append('\n');
                if (!m.role().isBlank()) sb.append("2 _ROLE ").append(m.role()).append('\n');
                for (String c : m.claims()) cite(sb, store.finding(c), sourceOf);
                own = true;
            }
        }
        for (Map.Entry<String, String> f : famOf.entrySet()) {
            String[] c = f.getKey().split("\\|", -1);
            if (c[1].isEmpty()) {
                // one parent: the husband's place unless the claims say she is a woman
                sb.append("0 ").append(f.getValue()).append(" FAM\n1 ").append("female".equals(sexOf.get(c[0])) ? "WIFE " : "HUSB ").append(xref.get(c[0])).append('\n');
                for (String kid : kidsOf.getOrDefault(f.getKey(), Set.of())) {
                    sb.append("1 CHIL ").append(xref.get(kid)).append('\n');
                    String r = belongs.getOrDefault(c[0] + "|" + kid, "");
                    if (!r.isEmpty() && !r.equals("birth")) sb.append("female".equals(sexOf.get(c[0])) ? "2 _MREL " : "2 _FREL ").append(RELATION_WORD.get(r)).append('\n');
                }
                continue;
            }
            if ("female".equals(sexOf.get(c[0])) || "male".equals(sexOf.get(c[1]))) c = new String[]{c[1], c[0]};
            sb.append("0 ").append(f.getValue()).append(" FAM\n1 HUSB ").append(xref.get(c[0])).append("\n1 WIFE ").append(xref.get(c[1])).append('\n');
            Graph.Edge m = couples.get(f.getKey());
            Finding mf = store.finding(m.findingId());
            sb.append("1 MARR\n");
            if (mf != null) { Matcher d = Pattern.compile("\\(([^)]+)\\)\\.\\s*$").matcher(mf.body().lines().findFirst().orElse("").strip()); if (d.find()) sb.append("2 DATE ").append(d.group(1)).append('\n'); }
            cite(sb, mf, sourceOf);
            for (String kid : kidsOf.getOrDefault(f.getKey(), Set.of())) {
                sb.append("1 CHIL ").append(xref.get(kid)).append('\n');
                // how the child belongs to each parent, as the programs that write _FREL and _MREL say it, when it is not by birth
                String fr = belongs.getOrDefault(c[0] + "|" + kid, ""), mr = belongs.getOrDefault(c[1] + "|" + kid, "");
                if (!fr.isEmpty() && !fr.equals("birth")) sb.append("2 _FREL ").append(RELATION_WORD.get(fr)).append('\n');
                if (!mr.isEmpty() && !mr.equals("birth")) sb.append("2 _MREL ").append(RELATION_WORD.get(mr)).append('\n');
            }
        }
        // the families, and the families they branched from
        List<String> families = new ArrayList<>(houseOf.keySet());
        for (int k = 0; k < families.size(); k++) {
            String of = FamilyHouses.branchOf(g, families.get(k));
            if (of != null && !houseOf.containsKey(of)) { houseOf.put(of, "@H" + (houseOf.size() + 1) + "@"); families.add(of); }
        }
        for (Map.Entry<String, String> h : houseOf.entrySet()) {
            String name = FamilyHouses.nameOf(FamilyHouses.labelOf(g, h.getKey()));
            sb.append("0 ").append(h.getValue()).append(" _HOUSE\n1 NAME ").append(name).append('\n');
            for (String a : FamilyHouses.aliasesOf(g, h.getKey())) if (!a.strip().equals(name)) sb.append("1 NAME ").append(a.strip()).append('\n');
            String seat = FamilyHouses.seat(g, h.getKey()), of = FamilyHouses.branchOf(g, h.getKey());
            if (!seat.isBlank()) sb.append("1 _SEAT ").append(seat).append('\n');
            if (of != null && houseOf.containsKey(of)) sb.append("1 _BRANCH ").append(houseOf.get(of)).append('\n');
        }
        for (Map.Entry<String, String> s : sourceOf.entrySet()) sb.append("0 ").append(s.getValue()).append(" SOUR\n1 TITL ").append(s.getKey()).append("\n1 REFN researchzosho:").append(s.getKey()).append('\n');
        return head + (own ? OWN_TAGS : "") + sb + "0 TRLR\n";
    }

    private static final Map<String, String> RELATION_WORD = Map.of("adopted", "Adopted", "foster", "Foster", "step", "Step");

    /** The header's note on the tags of the library's own that a file uses, for whoever reads the file, and for the library that reads it back. */
    static final String OWN_TAGS = "1 NOTE This file was written by ResearchZosho. Beside GEDCOM 5.5.1 it uses tags of its own, which ResearchZosho reads back.\n"
            + "2 CONT _NAMEKIND under a NAME says how the name came: birth, marriage, adoptive, mukoyoshi (婿養子, adopted and married into the family), nyufu (入夫 marriage), "
            + "succession, legal, taken-back, imposed, farm, immigrant, religious, art or aka.\n"
            + "2 CONT _NAMEDATE under a NAME says when the name was carried, as FROM and TO.\n"
            + "2 CONT _NAMEEVENT under a NAME names the event the name dates from: ADOP, MARR, or _SUCC for becoming the head of a family.\n"
            + "2 CONT A _HOUSE record is a family as a house, a line or a clan, with its NAME, its seat (_SEAT) and the family it is a branch of (_BRANCH).\n"
            + "2 CONT _MEMBER under a person is a membership of a _HOUSE, with its DATE (FROM and TO), how the person came in (_HOW), how they left (_LEFT) "
            + "and their role (_ROLE: head, heir or member).\n";

    /** A family's husband and wife by node id, as the export writes the family: {husband, wife}, "" for one it has not. */
    private static String[] spouses(String famKey, Map<String, String> sexOf) {
        String[] c = famKey.split("\\|", -1);
        if (c[1].isEmpty()) return "female".equals(sexOf.get(c[0])) ? new String[]{"", c[0]} : new String[]{c[0], ""};
        if ("female".equals(sexOf.get(c[0])) || "male".equals(sexOf.get(c[1]))) return new String[]{c[1], c[0]};
        return c;
    }

    /** An adoption as GEDCOM's ADOP event: its date, its kind in words (婿養子, heir), the family and which of its parents adopted, and its sources. */
    private static void adoption(StringBuilder sb, LibraryStore store, List<Graph.Edge> by, String fam, String who, Map<String, String> sourceOf) throws IOException {
        List<Finding> claims = new ArrayList<>();
        for (Graph.Edge e : by) { Finding f = store.finding(e.findingId()); if (f != null) claims.add(f); }
        String when = "", type = "";
        for (Finding f : claims) {
            if (when.isEmpty()) {
                when = FamilyDetail.get(f, "from");
                String bracket = FamilyAccount.lastBracket(firstWithoutNames(f));
                if (when.isBlank() && FamilyDate.parse(bracket) != null) when = bracket;
            }
            if (type.isEmpty()) {
                String kind = FamilyNameHistory.adoptionKind(f), said = FamilyDetail.get(f, "said");
                type = !said.isBlank() ? said : kind.equals("mukoyoshi") ? "婿養子" : kind.equals("heir") ? "heir" : "";
            }
        }
        sb.append("1 ADOP\n");
        if (!when.isBlank()) sb.append("2 DATE ").append(when.strip()).append('\n');
        if (!type.isBlank()) sb.append("2 TYPE ").append(type.strip()).append('\n');
        sb.append("2 FAMC ").append(fam).append("\n3 ADOP ").append(who).append('\n');
        for (Finding f : claims) cite(sb, f, sourceOf);
    }

    /**
     * Every name of a person as a NAME record, the latest first, which family-tree programs take as the one to show: its kind (TYPE, and
     * {@code _NAMEKIND}), the years it was carried as the claims give them ({@code _NAMEDATE}, or {@code _NAMEEVENT} for a name dated by its
     * event), its forms (ROMN for a Latin form, FONE for a kana reading) and its sources. A person with no name claim has one NAME, the name
     * they are filed under. Whether a tag of the library's own was written.
     */
    private static boolean names(StringBuilder sb, FamilyNameHistory.Index idx, Graph.Node node, Map<String, String> sourceOf) {
        List<FamilyNameHistory.Name> ns = idx.names(node.id());
        String label = bare(node.label());
        if (ns.stream().allMatch(FamilyNameHistory.Name::implicit)) { sb.append("1 NAME ").append(gedcomName(label)).append('\n'); return false; }
        FamilyNameHistory.Name latest = idx.latest(node.id());
        List<FamilyNameHistory.Name> out = new ArrayList<>();
        if (latest != null) out.add(latest);
        // the names the claims give, and the name the person is filed under; another name of an older library, under another family name, is only an other name
        for (FamilyNameHistory.Name n : ns) if (n != latest && (!n.implicit() || n.isForm(label))) out.add(n);
        boolean own = false;
        for (FamilyNameHistory.Name n : out) own |= name(sb, idx, node, n, sourceOf);
        return own;
    }

    private static boolean name(StringBuilder sb, FamilyNameHistory.Index idx, Graph.Node node, FamilyNameHistory.Name n, Map<String, String> sourceOf) {
        String text = n.written(), family = n.family(), given = n.given();
        // a name in characters is the NAME, and its romaji and kana are its forms, as family-tree programs write a Japanese name
        if (!FamilyForms.script(text).equals("han")) for (FamilyNameHistory.Form f : n.forms()) if (FamilyForms.script(f.text()).equals("han")) {
            String[] p = idx.parts(node.id(), f.text());
            text = f.text(); family = p[0]; given = p[0].isBlank() ? "" : p[1];
            break;
        }
        sb.append("1 NAME ").append(slashed(text, family, given)).append('\n');
        boolean own = false;
        if (!n.implicit() && !n.workedOut() && !n.kind().equals("unknown")) {
            sb.append("2 TYPE ").append(typeWord(idx, n)).append("\n2 _NAMEKIND ").append(n.kind()).append('\n');
            own = true;
        }
        // the years as the claims give them; what the library works out (a birth name from the birth, the end from the next name) it works out again
        String from = "", to = "";
        for (String c : n.claims()) {
            Finding f = idx.finding(c);
            if (f == null) continue;
            String fr = FamilyDetail.get(f, "from"), t = FamilyDetail.get(f, "to");
            if (from.isEmpty() && !fr.isBlank() && !fr.equalsIgnoreCase("event")) from = fr.strip();
            if (from.isEmpty() && fr.isBlank() && !n.fromEvent()) { FamilyDate d = FamilyChecks.claimDate(f); if (d != null) from = d.written().strip(); }
            if (to.isEmpty() && !t.isBlank() && !t.equalsIgnoreCase("event")) to = t.strip();
        }
        Finding event = n.fromEvent() ? idx.finding(n.event()) : null;
        String tag = event == null || event.triple() == null ? "" : switch (event.triple().predicate()) {
            case "adopted-by" -> "ADOP"; case "married-to" -> "MARR"; case FamilyHouses.MEMBER, "heir-of" -> "_SUCC"; default -> "";
        };
        if (!n.workedOut() && !tag.isEmpty()) { sb.append("2 _NAMEEVENT ").append(tag).append('\n'); own = true; }
        else if (!n.workedOut() && (!from.isEmpty() || !to.isEmpty())) {
            sb.append("2 _NAMEDATE ").append(from.isEmpty() ? "TO " + to : to.isEmpty() ? "FROM " + from : "FROM " + from + " TO " + to).append('\n');
            own = true;
        }
        for (String t : n.texts()) {
            if (t.equals(text)) continue;
            String sc = FamilyForms.script(t);
            // a romanised form with its family name between slashes only where the name's family part is written in it; as it stands otherwise
            if (sc.equals("latin")) sb.append("2 ROMN ").append(FamilyForms.script(family).equals("latin") && word(t, family) ? slashed(t, family, "") : t.strip()).append("\n3 TYPE romaji\n");
            else if (sc.equals("kana")) {
                String[] w = t.strip().split("[\\s　]+");
                sb.append("2 FONE ").append(w.length == 2 ? w[1] + " /" + w[0] + "/" : t.strip()).append("\n3 TYPE kana\n");
            }
        }
        for (String c : n.claims()) cite(sb, idx.finding(c), sourceOf);
        return own;
    }

    /**
     * A name as GEDCOM writes it, with the family name between slashes where the parts are known: "健二 /遠藤/", "Tom /Hale/ Jr.", and
     * "/Morita/ Kenji" for a Latin name written family name first, so that it reads back in its own order. As {@link #gedcomName} otherwise.
     */
    static String slashed(String text, String family, String given) {
        String t = text.strip(), f = family == null ? "" : family.strip(), gv = given == null ? "" : given.strip();
        if (f.isEmpty()) return gedcomName(t);
        if (!FamilyForms.script(t).equals("latin")) return gv.isEmpty() ? "/" + f + "/" : gv + " /" + f + "/";
        Matcher m = Pattern.compile("(?<![\\p{L}])" + Pattern.quote(f) + "(?![\\p{L}])").matcher(t);
        if (m.find()) return (t.substring(0, m.start()) + "/" + f + "/" + t.substring(m.end())).replaceAll("\\s+", " ").strip();
        return gv.isEmpty() ? gedcomName(t) : gv + " /" + f + "/";
    }

    /** Whether a family name stands in a Latin name as a word of its own. */
    private static boolean word(String text, String family) {
        return !family.isBlank() && Pattern.compile("(?<![\\p{L}])" + Pattern.quote(family.strip()) + "(?![\\p{L}])").matcher(text).find();
    }

    /**
     * A name's kind as NAME TYPE writes it: GEDCOM's word where it has one, else the source's own words, else the library's own short word
     * for the kind (mukoyoshi, succession), which {@code _NAMEKIND} beside it explains and which reads back as no word of the source's.
     */
    private static String typeWord(FamilyNameHistory.Index idx, FamilyNameHistory.Name n) {
        return switch (n.kind()) {
            case "birth" -> "birth";
            case "marriage" -> "married";
            case "aka" -> "aka";
            case "immigrant" -> "immigrant";
            case "art" -> "professional";
            // the source's own words only where they say how the name came (婿養子), as the names page shows them; words that only lead up
            // to the name ("Isamu's son") are no type
            default -> FamilyNamePages.saysHow(idx, n) ? n.said() : n.kind();
        };
    }

    /** A membership's years as a GEDCOM period: "FROM 1932 TO 1940", "FROM 1932", "TO 1940"; "" when neither is known. */
    private static String period(FamilyDate from, FamilyDate to) {
        String a = dateText(from), b = dateText(to);
        return a.isEmpty() && b.isEmpty() ? "" : a.isEmpty() ? "TO " + b : b.isEmpty() ? "FROM " + a : "FROM " + a + " TO " + b;
    }

    /** A date as GEDCOM writes one: as written when it is a GEDCOM date ("12 MAR 1932", "1932"), else its year, with ABT, BEF, AFT or BET for a date that is not exact. */
    private static String dateText(FamilyDate d) {
        if (d == null) return "";
        return switch (d.qualifier()) {
            case "about" -> "ABT " + d.year();
            case "before" -> "BEF " + d.year();
            case "after" -> "AFT " + d.year();
            case "between" -> "BET " + d.year() + " AND " + d.until();
            default -> d.written().strip().matches("(?i)(\\d{1,2}\\s+)?([A-Z]{3}\\s+)?\\d{3,4}") ? d.written().strip() : String.valueOf(d.year());
        };
    }

    /** A name as GEDCOM writes it, the family name between slashes: "Isamu /Endo/". A name in Chinese characters or of one word is written as it stands. */
    static String gedcomName(String name) {
        String n = name.strip();
        if (n.isEmpty() || n.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN || Character.UnicodeScript.of(c) == Character.UnicodeScript.HIRAGANA
                || Character.UnicodeScript.of(c) == Character.UnicodeScript.KATAKANA || Character.UnicodeScript.of(c) == Character.UnicodeScript.HANGUL)) return n;
        int last = n.lastIndexOf(' ');
        return last < 0 ? n : n.substring(0, last) + " /" + n.substring(last + 1) + "/";
    }

    /** A claim's first line with the names of its triple taken out, so the brackets at its end are the date and no name's year. */
    private static String firstWithoutNames(Finding f) {
        String first = f.body().lines().findFirst().orElse("");
        if (f.triple() != null) for (String n : new String[]{f.triple().subject(), f.triple().object()}) if (n != null && !n.isBlank()) first = first.replace(n, " ");
        return first;
    }

    /**
     * An open question as a research note in the file: what it asks, without the instructions the library gives its own research and
     * without the person's name in front, which the importing library puts back.
     */
    static String todoText(String text) {
        String t = text.replaceAll("\\s+", " ").strip();
        int asks = t.indexOf(" answer each of these questions on its own");
        if (asks >= 0) {
            int from = t.indexOf("records.", asks);
            t = from < 0 ? t : t.substring(from + "records.".length()).strip();
            for (String lead : List.of(" The family around this person, as leads:", " People the records name beside this person", " The brothers and sisters have the same parents"))
                if (t.contains(lead)) t = t.substring(0, t.indexOf(lead)).strip();
        } else if (t.contains(": ")) t = t.substring(t.indexOf(": ") + 2).strip();
        return t;
    }

    /** The sources of a claim under its event, each pointing at one SOUR record per site or file; a draft says it is one. */
    private static void cite(StringBuilder sb, Finding f, Map<String, String> sourceOf) {
        if (f == null) return;
        for (Finding.Source s : f.sources()) {
            String where = FamilyChecks.from(s.locator());
            String site = where.contains(", ") ? where.substring(0, where.indexOf(", ")) : where;
            if (site.isBlank()) continue;
            String sx = sourceOf.computeIfAbsent(site, k -> "@S" + (sourceOf.size() + 1) + "@");
            sb.append("2 SOUR ").append(sx).append('\n');
            String edition = s.edition().replaceAll("\\s*\\{page: [^}]*\\}", "");   // the page unit is the library's own bookkeeping
            String page = s.locator().startsWith("file:") ? where + (edition.isBlank() ? "" : ", " + edition) : s.locator();
            sb.append("3 PAGE ").append(Acquisitions.compress(page.replaceAll("\\s+", " "), 240)).append('\n');
        }
        if (f.state() == Finding.State.draft) sb.append("2 NOTE A draft: nobody has checked this against a record yet.\n");
    }

    private static String label(Graph g, String id) {
        Graph.Node n = g.node(id);
        return n == null ? id : n.label();
    }
}
