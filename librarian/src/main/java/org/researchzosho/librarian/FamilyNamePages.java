package org.researchzosho.librarian;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A person's names and families, and a family's page, at the terminal and on the web: one can drill in and out, from a tree box to the
 * person, to a family they belonged to, to another member, to that member's other family, and back. The terminal and the web page say
 * the same, because both are written by the same sentences here; only the way a person, a family and a claim are written differs (a name
 * and a command at the terminal, a link on the web). Every command and every link keeps the person's label, which is how the library
 * finds them; only the prose shows the names worked out over the life ({@link FamilyNameHistory}).
 */
public final class FamilyNamePages {

    private FamilyNamePages() { }

    // ── the heading of a person, everywhere it is written ──────────────────────────────────────────────────────────────

    /**
     * A person's heading, for prose: the latest name with the birth name beside it ("森田健二 (born 遠藤)"), and the bracket that tells namesakes
     * apart ({@link #named}). The name as given when the graph has nobody by it, so a line about somebody the library does not hold still reads.
     */
    public static String heading(Graph g, String name) {
        if (name == null || name.isBlank()) return name == null ? "" : name;
        String id = g.nodeIdOf(name);
        if (g.node(id) == null) return name;
        return named(FamilyNameHistory.of(g), g, id);
    }

    /** A person as prose names them: the heading, with the bracket of the entry's name that tells namesakes apart ({@link #withBracket}). */
    static String named(FamilyNameHistory.Index idx, Graph g, String id) {
        Graph.Node n = g.node(id);
        return n == null ? id : withBracket(idx.heading(id), n.label());
    }

    /** The latest name of a person, in the script of their label; the label when no name is known. */
    public static String shown(FamilyNameHistory.Index idx, Graph g, String id) {
        Graph.Node node = g.node(id);
        if (node == null) return id;
        FamilyNameHistory.Name latest = idx.latest(id);
        return latest == null ? node.label() : latest.shown(FamilyForms.script(node.label()));
    }

    private static final Pattern BRACKET = Pattern.compile("\\s*[(（]([^)）]*)[)）]\\s*$");

    /**
     * A heading with the bracket of the entry's name beside it, when the heading does not carry it: the bracket that tells two entries of one
     * name apart ("Ann Hale (born 1941)", "John Ellis (his son)") belongs to the person, not to one of their names, so a heading worked out
     * from a later name keeps it: "Ann Hart (born 1941)", "Mary Ellis (born Hale; born 1941)". The heading as it is otherwise.
     */
    static String withBracket(String heading, String label) {
        if (heading == null || label == null) return heading;
        Matcher m = BRACKET.matcher(label);
        if (!m.find()) return heading;
        String inner = m.group(1).strip();
        if (inner.isEmpty() || heading.contains(inner)) return heading;
        Matcher h = BRACKET.matcher(heading);
        return h.find() ? heading.substring(0, h.start()) + " (" + h.group(1).strip() + "; " + inner + ")" : heading + " (" + inner + ")";
    }

    /** What the heading has in its brackets after "born": the birth family name, or the whole birth name; "" when it has none. */
    public static String bornAs(FamilyNameHistory.Index idx, Graph g, String id) {
        if (g.node(id) == null) return "";
        String h = idx.heading(id), s = shown(idx, g, id);
        return h.startsWith(s + " (born ") && h.endsWith(")") ? h.substring(s.length() + " (born ".length(), h.length() - 1) : "";
    }

    // ── how a page writes things ────────────────────────────────────────────────────────────────────────────────────────

    /** How a page writes words, a person, a family, the claims a sentence rests on, and a command: text at the terminal, links on the web. */
    private interface Out {
        String words(String s);
        String person(String heading, String label);
        String family(String label);
        String claims(List<String> ids);
        /** A command and what it does, at the terminal; null on the web, where the links are the way in. */
        String command(String command, String does);
    }

    private static String shortId(String id) { return id.replaceFirst("^(F-\\d+).*", "$1"); }

    private static final Out TERMINAL = new Out() {
        public String words(String s) { return s; }
        public String person(String heading, String label) { return heading; }
        public String family(String label) { return label; }
        public String claims(List<String> ids) { return ids.isEmpty() ? "" : " [" + String.join(", ", ids.stream().map(FamilyNamePages::shortId).distinct().toList()) + "]"; }
        public String command(String command, String does) { return command + " " + does; }
    };

    private static final Out WEB = new Out() {
        public String words(String s) { return FamilyTree.esc(s); }
        public String person(String heading, String label) { return "<a href=\"/person?name=" + enc(label) + "\">" + FamilyTree.esc(heading) + "</a>"; }
        public String family(String label) { return "<a href=\"/family?name=" + enc(label) + "\">" + FamilyTree.esc(label) + "</a>"; }
        public String claims(List<String> ids) {
            StringBuilder b = new StringBuilder();
            for (String id : ids.stream().distinct().toList()) b.append(" <a href=\"/entry/").append(FamilyTree.esc(id)).append("\">").append(FamilyTree.esc(shortId(id))).append("</a>");
            return b.toString();
        }
        public String command(String command, String does) { return null; }
    };

    /** One thing a page lists: the sentence, and the lines under it (other written forms, commands). */
    private record Entry(String text, List<String> under) { }

    // ── a name, in words ────────────────────────────────────────────────────────────────────────────────────────────────

    /** How a name of a kind came, as a sentence says it. A kind a later version adds is said by its words in {@link FamilyNameHistory#KINDS}. */
    static String how(String kind) { return how(kind, ""); }

    /**
     * The same, with the family name the name carries, for the kinds that say which family the person married into: "the name he took when
     * he married into the 森田 family as 婿養子". A 婿養子 and an 入夫 are always a husband.
     */
    static String how(String kind, String family) {
        String into = family == null || family.isBlank() ? "his wife's family" : "the " + family.strip() + " family";
        return switch (FamilyNameHistory.kind(kind)) {
            case "birth" -> "the name at birth";
            case "marriage" -> "the name taken at marriage";
            case "adoptive" -> "the name taken on adoption";
            case "mukoyoshi" -> "the name he took when he married into " + into + " as 婿養子";
            case "nyufu" -> "the name he took when he married into " + into + " as 入夫";
            case "succession" -> "the name taken on becoming head of the family";
            case "legal" -> "a name taken by law, by a court, or under a will";
            case "taken-back" -> "a name taken back after a divorce or an ended adoption";
            case "imposed" -> "a name imposed by law";
            case "farm" -> "a name taken from the farm or house the person lived at";
            case "immigrant" -> "the name taken on moving to a new country";
            case "religious" -> "a religious or posthumous name, used beside the others";
            case "art" -> "an art, pen or professional name, used beside the others";
            case "aka" -> "another name the person was known by";
            case "hereditary" -> "the name the heads of the family carried, one after another";
            case "earlier" -> "the earlier name; how it changed is not known";
            case "later" -> "the later name; how it changed is not known";
            case "unknown" -> "a name; the library does not know yet how it came";
            default -> FamilyNameHistory.KINDS.getOrDefault(kind, "a name; the library does not know yet how it came");
        };
    }

    /**
     * The years after a name's kind, with its comma: "in 1932" for a name taken then and carried on, "from 1905" for a name at birth or one
     * carried beside the others, "from 1905 to 1932", "used in records from 1915 to 1925"; "" when nothing dates it.
     */
    static String yearsSaid(FamilyNameHistory.Index idx, String id, FamilyNameHistory.Name n) {
        String p = period(idx, id, n);
        if (p.isEmpty()) return "";
        boolean taken = n.replaces() && !FamilyNameHistory.kind(n.kind()).equals("birth") && !FamilyNameHistory.kind(n.kind()).equals("unknown") && (!n.implicit() || n.workedOut());
        return ", " + (taken && n.to() == null && p.startsWith("from ") ? "in " + p.substring("from ".length()) : p);
    }

    /** The years a name was carried, as far as anything says: "from 1905 to 1932", "from 1932", "used in records from 1915 to 1925"; "" when nothing dates it. */
    static String period(FamilyNameHistory.Index idx, String id, FamilyNameHistory.Name n) {
        String from = n.from() == null ? "" : n.from().phrase(), to = n.to() == null ? "" : n.to().phrase();
        if (!from.isEmpty() && !to.isEmpty()) return "from " + from + " to " + to;
        if (!from.isEmpty()) return "from " + from;
        if (!to.isEmpty()) return "until " + to;
        int[] w = idx.useWindow(id, n);
        if (w == null) return "";
        return w[0] == w[1] ? "used in a record of " + w[0] : "used in records from " + w[0] + " to " + w[1];
    }

    /**
     * One name as a page lists it: the name, how it came and its years; what the library worked out, in brackets; the source's own words
     * for it; where it came from; and the codes of the claims it rests on, last.
     */
    private static String nameText(Out o, Graph g, FamilyNameHistory.Index idx, String id, FamilyNameHistory.Name n) {
        Graph.Node node = g.node(id);
        String label = node == null ? id : node.label();
        String what = n.implicit() && !n.workedOut() ? (n.isForm(label) ? "the name your library files this person under" : "another name your library has for this person; the library does not know yet how it came") : how(n.kind(), n.family());
        StringBuilder b = new StringBuilder(o.words(n.written() + ": " + what + yearsSaid(idx, id, n) + "."));
        List<String> codes = new ArrayList<>();
        if (n.workedOut()) {
            // what it was worked out from; where a source gives the kind, only its year was
            boolean yearOnly = n.basis().equals(FamilyNameHistory.BASIS_MARRIAGE_YEAR) || n.basis().equals(FamilyNameHistory.BASIS_ENTRY_YEAR);
            String from = switch (n.basis()) {
                case FamilyNameHistory.BASIS_ENTRY, FamilyNameHistory.BASIS_ENTRY_YEAR -> "the entry into the family";
                case FamilyNameHistory.BASIS_BIRTH -> "a birth parent's family name";
                case FamilyNameHistory.BASIS_FORMERLY -> "the words that say which name came first";
                default -> "the marriage";
            };
            b.append(o.words(yearOnly ? " (The library worked out the year from " + from + ".)" : " (The library worked this out from " + from + ". No source says it in words.)"));
            if (!n.event().isBlank()) codes.add(n.event());
        }
        else if (!n.event().isBlank() && !n.implicit()) {
            Finding e = idx.finding(n.event());
            String cause = eventWords(g, e), kind = FamilyNameHistory.kind(n.kind());
            // "the name taken at marriage" already says it came with the marriage
            boolean said = cause.equals("the marriage") && kind.equals("marriage") || cause.equals("the adoption") && kind.equals("adoptive");
            if (n.fromEvent()) b.append(o.words(" Its year is the year of " + cause + "."));
            else if (!said) b.append(o.words(" It came with " + cause + "."));
            codes.add(e == null ? n.event() : e.id());
        }
        if (saysHow(idx, n)) b.append(o.words(" The source says: \"" + n.said() + "\"."));
        b.append(sources(o, idx, n, codes));
        b.append(o.claims(codes));
        return b.toString();
    }

    /**
     * Whether the words kept with a name ({@code said}) say how it came, and so are shown as the source's own words for it: words that say
     * its kind ({@link FamilyNameHistory#saysKind}: 婿養子, née, "took her husband's name"). For a name whose kind is not known yet, words
     * that say some kind and stand in the words of the source its claims quote: a reader keeps the model's own word for the kind there, and
     * that is no source's word. Words that only lead up to the name, such as "Isamu's son" or "became", say nothing of how it came and are
     * left out.
     */
    static boolean saysHow(FamilyNameHistory.Index idx, FamilyNameHistory.Name n) {
        String said = n.said() == null ? "" : n.said().strip();
        if (said.isEmpty()) return false;
        String kind = FamilyNameHistory.kind(n.kind());
        if (!kind.equals("unknown")) return FamilyNameHistory.saysKind(said, kind);
        if (FamilyNameHistory.SAYS.keySet().stream().noneMatch(k -> FamilyNameHistory.saysKind(said, k))) return false;
        String plain = FamilyQuestions.plain(said);
        for (String c : n.claims()) { Finding f = idx.finding(c); if (f != null && FamilyQuestions.plain(f.body()).contains(plain)) return true; }
        return false;
    }

    /** The event a name came with, as a sentence names it: "the adoption", "the marriage". */
    static String eventWords(Graph g, Finding e) {
        String p = e == null || e.triple() == null ? "" : g.predicateOf(e.triple().predicate());
        return switch (p == null ? "" : p) {
            case "adopted-by" -> "the adoption";
            case "married-to" -> "the marriage";
            case "member-of" -> "entering the family";
            case "heir-of" -> "becoming the heir";
            default -> "the event that caused it";
        };
    }

    /**
     * Where a name's claims come from, each with what it rests on: the family's answer to a question about names, what the owner told the
     * library, or a source with its kind (a record, published, a clue only); and whether the owner accepted it. The claims' codes are added
     * to {@code codes}, which the sentence ends with.
     */
    private static String sources(Out o, FamilyNameHistory.Index idx, FamilyNameHistory.Name n, List<String> codes) {
        List<String> parts = new ArrayList<>();
        int more = 0;
        for (String c : n.claims()) {
            Finding f = idx.finding(c);
            if (f == null) continue;
            codes.add(f.id());
            if (parts.size() == 4) { more++; continue; }
            boolean accepted = f.state() == Finding.State.accepted && f.review() != null && "person".equals(f.review().reviewer());
            String loc = f.sources().isEmpty() ? "" : f.sources().get(0).locator();
            String where = loc.startsWith("told://family-answer/") ? "your family's answer to a question" : loc.startsWith("told://") ? "what you told the library"
                    : (loc.isBlank() ? "" : FamilyChecks.from(loc) + " ") + "(" + Evidence.of(f).word() + (accepted ? ", accepted by you" : "") + ")";
            if (!parts.contains(where)) parts.add(where);
        }
        if (parts.isEmpty()) return "";
        if (more > 0) parts.add(more == 1 ? "1 more" : more + " more");
        return o.words(" From " + FamilyReads.and(parts) + ".");
    }

    /** The other written forms of a name, each with where it came from when the library knows it. */
    private static List<String> formLines(Out o, Map<String, List<String>> aliasFrom, FamilyNameHistory.Name n) {
        List<String> out = new ArrayList<>();
        for (FamilyNameHistory.Form f : n.forms()) {
            if (f.text().equals(n.written())) continue;
            List<String> where = new ArrayList<>();
            for (String l : aliasFrom.getOrDefault(f.text(), List.of())) where.add(FamilyChecks.from(l) + ", " + Evidence.of(l).word());
            out.add(o.words("also written " + f.text() + (where.isEmpty() ? "" : " (from " + String.join("; ", where) + ")")));
        }
        return out;
    }

    // ── a membership, in words ──────────────────────────────────────────────────────────────────────────────────────────

    /** How a person came into a family, as a sentence says it; "" when no claim says. */
    static String cameIn(String how) {
        return switch (how == null ? "" : how) {
            case "birth" -> "born into it";
            case "marriage" -> "married into it";
            case "adoption" -> "adopted into it";
            case "mukoyoshi" -> "married into it as 婿養子";
            case "nyufu" -> "married into it as 入夫";
            case "succession" -> "came into it by succession";
            case "founding" -> "founded it";
            case "", "unstated" -> "";
            default -> "came in by " + how.replace('-', ' ');
        };
    }

    /** How a person left a family, after "left it"; "" when no claim says. */
    static String wentOut(String left) {
        return switch (left == null ? "" : left) {
            case "marriage-out" -> " on marrying out";
            case "adoption-out" -> " on being adopted into another family";
            case "branch" -> " to found a branch";
            case "divorce" -> " on a divorce";
            case "adoption-ended" -> " when the adoption ended";
            default -> "";
        };
    }

    /** One membership in words, without its claims' codes, which the sentence ends with: "married into it as 婿養子, in 1932". */
    private static String membershipText(Out o, Graph g, FamilyHouses.Membership m) {
        StringBuilder b = new StringBuilder();
        String in = cameIn(m.how()), role = m.role(), from = m.from() == null ? "" : m.from().phrase();
        boolean ranked = role.equals("head") || role.equals("heir");
        if (!in.isEmpty()) b.append(o.words(in + (ranked ? " as its " + role : "") + (from.isEmpty() ? "" : " in " + from)));
        else if (ranked) b.append(o.words("its " + role + (from.isEmpty() ? "" : " from " + from)));
        else b.append(o.words("a member" + (from.isEmpty() ? "" : " from " + from) + "; how they came in is not written down"));
        if (!m.cameFrom().isBlank()) {
            b.append(o.words(", coming from ")).append(o.family(FamilyHouses.labelOf(g, m.cameFrom())));
            if (m.workedOut()) b.append(o.words(" (the library worked this out from a parent's family at the birth, as no source says where they came from)"));
        }
        if (m.left().equals("death")) b.append(o.words("; a member until death" + (m.to() == null ? "" : " in " + m.to().phrase())));
        else if (m.to() != null || !m.left().isBlank()) {
            b.append(o.words("; left it" + (m.to() == null ? "" : " in " + m.to().phrase())
                    + (m.toWorkedOut() ? " (the library worked out the year from when they came into their next family, as no source says when they left)" : "") + wentOut(m.left())));
            if (!m.wentTo().isBlank()) b.append(o.words(", going to ")).append(o.family(FamilyHouses.labelOf(g, m.wentTo())));
        } else if (!m.wentTo().isBlank()) b.append(o.words("; later also a member of ")).append(o.family(FamilyHouses.labelOf(g, m.wentTo())));
        return b.toString();
    }

    /**
     * Memberships as the pages show them: one that says only that a person was a member of a family (no way in, no role, no leaving), beside
     * one of the same person in the same family that says how they came in, in the same year or with no year of its own, is that same
     * membership. It is shown once, with the claims of both, and never as "how they came in is not written down" beside the way they came in.
     */
    static List<FamilyHouses.Membership> asShown(List<FamilyHouses.Membership> ms) {
        List<FamilyHouses.Membership> out = new ArrayList<>(ms);
        for (FamilyHouses.Membership bare : ms) {
            if (!saysOnlyMember(bare)) continue;
            int at = -1;
            for (int i = 0; i < out.size() && at < 0; i++) {
                FamilyHouses.Membership m = out.get(i);
                if (m == bare || saysOnlyMember(m) || !m.person().equals(bare.person()) || !m.family().equals(bare.family())) continue;
                if (bare.from() != null && m.from() != null && bare.from().year() != m.from().year()) continue;
                at = i;
            }
            if (at < 0) continue;
            FamilyHouses.Membership m = out.get(at);
            List<String> claims = new ArrayList<>(m.claims());
            for (String c : bare.claims()) if (!claims.contains(c)) claims.add(c);
            out.set(at, new FamilyHouses.Membership(m.person(), m.family(), m.how(), m.left(), m.from() != null ? m.from() : bare.from(), m.to(), m.role(), m.event(), m.eventOut(), claims,
                    m.cameFrom().isBlank() ? bare.cameFrom() : m.cameFrom(), m.wentTo(), m.cameFrom().isBlank() ? bare.workedOut() : m.workedOut(), m.toWorkedOut()));
            for (int i = 0; i < out.size(); i++) if (out.get(i) == bare) { out.remove(i); break; }
        }
        return out;
    }

    /** Whether a membership says only that the person was a member: no way in, no role, no leaving, no end. */
    private static boolean saysOnlyMember(FamilyHouses.Membership m) {
        return (m.how().isBlank() || m.how().equals("unstated")) && m.role().isBlank() && m.left().isBlank() && m.to() == null && m.wentTo().isBlank();
    }

    private static String familyCommand(Out o, String label, String does) { return o.command("researchzosho genealogy family " + shellQuoted(label), does); }

    private static String personCommand(Out o, String label, String does) { return o.command("researchzosho genealogy names " + shellQuoted(label), does); }

    /**
     * A name as a suggested command writes it, so that a shell gives the program back exactly these words: in double quotes, as every command
     * the library prints writes a name; in single quotes when the name has a character a shell reads inside double quotes (a double quote, a
     * backslash, $, a backquote or !), a single quote in it written '\''. A tree file's nickname in quotes, Mary "Ruth" Ellis, is a label too.
     */
    public static String shellQuoted(String s) {
        String t = s == null ? "" : s;
        return t.matches("(?s).*[\"\\\\$`!].*") ? Fields.quoted(t) : "\"" + t + "\"";
    }

    // ── a person's names and families ──────────────────────────────────────────────────────────────────────────────────

    /**
     * A person's names and families as a page lists them, the questions that wait about them, and what is not settled about them and asked of
     * nobody, as they are outside close family ({@link FamilyNameQuestions#notAsked}).
     */
    private record PersonPage(List<Entry> names, List<Entry> families, List<String> addressed, List<FamilyNameQuestions.Question> questions, List<FamilyNameQuestions.Question> notSettled, boolean onlyTheLabel) {
        /** Whether the page says more than the heading does: another name, another written form, a source for a name, a family, a question. */
        boolean saysMore() { return !onlyTheLabel || !families.isEmpty() || !addressed.isEmpty() || !questions.isEmpty() || !notSettled.isEmpty(); }
    }

    /** What a page says above the forms a text addresses the person by, which are no names of theirs. */
    static final String ADDRESSED_AS = "Also addressed as";

    /**
     * How a text addresses the person ({@link FamilyNameHistory.Index#addressedAs}), each form once with where it came from: the sources of
     * the claims that wrote it, else the sources of the other name.
     */
    private static List<String> addressedLines(Out o, FamilyNameHistory.Index idx, Map<String, List<String>> aliasFrom, List<FamilyNameHistory.Index.Address> addressed) {
        List<String> out = new ArrayList<>();
        for (FamilyNameHistory.Index.Address a : addressed) {
            List<String> where = new ArrayList<>();
            List<String> codes = new ArrayList<>();
            for (String c : a.claims()) {
                Finding f = idx.finding(c);
                if (f == null) continue;
                codes.add(f.id());
                for (Finding.Source src : f.sources()) { String w = FamilyChecks.from(src.locator()) + ", " + Evidence.of(src.locator()).word(); if (!w.isBlank() && !where.contains(w)) where.add(w); }
            }
            if (where.isEmpty()) for (String l : aliasFrom.getOrDefault(a.text(), List.of())) { String w = FamilyChecks.from(l) + ", " + Evidence.of(l).word(); if (!where.contains(w)) where.add(w); }
            out.add(o.words(a.text() + (where.isEmpty() ? "" : " (from " + String.join("; ", where) + ")")) + o.claims(codes));
        }
        return out;
    }

    /** What a page says above what is not settled about a person and asked of nobody; each thing says whom it is held back for. */
    static final String NOT_SETTLED = "Not settled, and asked of nobody. What the sources say:";

    /** Why a question is asked of nobody, naming the people it is about: "Your library asks nobody about this, because Tom Hale is outside your close family." */
    static String heldBack(Graph g, FamilyNameQuestions.Question q) {
        String who = FamilyNameQuestions.outsideSaid(g, q);
        return "Your library asks nobody about this, because " + who + " outside your close family.";
    }

    private static PersonPage personView(Out o, LibraryStore store, Graph g, String id) throws IOException {
        FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
        String label = g.node(id).label();
        Map<String, List<String>> aliasFrom = Graph.aliasSources(store, label);
        List<Entry> names = new ArrayList<>();
        List<FamilyNameHistory.Name> ns = idx.names(id);
        for (FamilyNameHistory.Name n : ns) {
            List<String> under = new ArrayList<>(formLines(o, aliasFrom, n));
            // a name the library worked out as the marriage's: one answer changes it
            String change = n.cameWithTheMarriage() ? o.command("researchzosho genealogy who " + shellQuoted(label), "asks how this name came, if it came another way than with the marriage.") : null;
            if (change != null) under.add(change);
            names.add(new Entry(nameText(o, g, idx, id, n), under));
        }
        boolean onlyTheLabel = ns.size() == 1 && ns.get(0).implicit() && ns.get(0).forms().stream().allMatch(f -> f.text().equals(ns.get(0).written()));
        List<Entry> families = new ArrayList<>();
        for (FamilyHouses.Membership m : asShown(FamilyHouses.families(g, id))) {
            String fam = FamilyHouses.labelOf(g, m.family());
            List<String> under = new ArrayList<>();
            String c = familyCommand(o, fam, "shows that family: its heads and its members, with where each came from and went to.");
            if (c != null) under.add(c);
            for (String[] other : new String[][]{{m.cameFrom(), "shows the family they came from."}, {m.wentTo(), "shows the family they went to."}}) {
                if (other[0].isBlank()) continue;
                String x = familyCommand(o, FamilyHouses.labelOf(g, other[0]), other[1]);
                if (x != null) under.add(x);
            }
            families.add(new Entry(o.family(fam) + o.words(": ") + membershipText(o, g, m) + o.words(".") + o.claims(m.claims()), under));
        }
        FamilyNameQuestions.Asking asking = FamilyNameQuestions.asking(store, Set.of(id));
        return new PersonPage(names, families, addressedLines(o, idx, aliasFrom, idx.addressedAs(id)), asking.asked(), asking.notAsked(), onlyTheLabel);
    }

    private static void print(PrintStream out, String indent, List<Entry> entries) {
        for (Entry e : entries) {
            out.println(indent + e.text());
            for (String u : e.under()) out.println(indent + "  " + u);
        }
    }

    /** The node a typed name is, trying the modern characters too, as the life does; null when the graph has nobody by it. */
    private static String find(Graph g, String name) {
        if (name == null || name.isBlank()) return null;
        String id = g.nodeIdOf(name);
        if (g.node(id) == null) id = g.nodeIdOf(KanjiForms.modern(name));
        return g.node(id) == null ? null : id;
    }

    /** {@code genealogy names <person>}: the person's page. {@code person}: the label the library writes them under. Returns the exit code. */
    public static int cliNames(LibraryStore store, String person, PrintStream out) throws IOException {
        Graph g = FamilyPeople.view(store);
        String id = find(g, person);
        if (id == null) {
            out.println("Nobody in your library is called \"" + person + "\". The command researchzosho map " + shellQuoted(person) + " shows the names your library holds that are close to it.");
            return 1;
        }
        String label = g.node(id).label();
        if (FamilyHouses.isFamily(g, id)) {
            out.println(label + " is a family, not a person. The command " + familyCommand(TERMINAL, label, "shows its heads and its members."));
            return 0;
        }
        FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
        String heading = named(idx, g, id);
        out.println(heading);
        if (!shown(idx, g, id).equals(label)) out.println("Your library files this person under " + label + ". Use that name in commands.");
        PersonPage p = personView(TERMINAL, store, g, id);
        out.println("\nNames, in the order of the life:");
        print(out, "  ", p.names());
        if (!p.addressed().isEmpty()) {
            out.println("\n" + ADDRESSED_AS + ", which is no name of this person:");
            for (String a : p.addressed()) out.println("  " + a);
        }
        out.println("\nFamilies:");
        if (p.families().isEmpty()) out.println("  No family is written down for this person. A family is written down when a text you read speaks of it as a family, such as the Morita family or 森田家.");
        else print(out, "  ", p.families());
        List<String> joined = FamilyLinks.about(store, g, id);
        if (!joined.isEmpty()) {
            out.println("\nJoined from the evidence:");
            for (String j : joined) out.println("  " + j);
            out.println("  " + FamilyLinks.TAKE_BACK);
        }
        List<String> aside = FamilyDoubts.about(store, g, id);
        if (!aside.isEmpty()) {
            out.println("\n" + FamilyDoubts.HEADING + ":");
            for (String a : aside) out.println("  " + a);
            out.println("  " + FamilyDoubts.WHAT);
        }
        List<String> readAs = FamilyDoubts.readAsAbout(store, g, id);
        if (!readAs.isEmpty()) {
            out.println("\n" + FamilyDoubts.READ_AS_HEADING + ":");
            for (String a : readAs) out.println("  " + a);
        }
        if (!p.questions().isEmpty()) {
            out.println("\nQuestions for your family:");
            for (FamilyNameQuestions.Question q : p.questions()) out.println("  " + q.text());
            out.println("  researchzosho genealogy who asks them one at a time, and says what each answer will do.");
        }
        if (!p.notSettled().isEmpty()) {
            out.println("\n" + NOT_SETTLED);
            for (FamilyNameQuestions.Question q : p.notSettled()) out.println("  " + q.text() + " " + heldBack(g, q));
            out.println("  To ask about it all the same: researchzosho genealogy who " + shellQuoted(label) + ". It asks you the questions about this person, one at a time.");
        }
        out.println("\nMore:");
        out.println("  researchzosho genealogy life " + shellQuoted(label) + " shows everything your library holds of this person's life, in order of date.");
        out.println("  researchzosho genealogy tree " + shellQuoted(label) + " draws the family around this person as a picture.");
        return 0;
    }

    /**
     * The names and families at the end of a person's life ({@code genealogy life}), with the commands that open each family; "" when the
     * person has one name, written one way, and no family.
     */
    public static String lifeBlock(LibraryStore store, String name) throws IOException {
        Graph g = FamilyPeople.view(store);
        String id = find(g, name);
        if (id == null || FamilyHouses.isFamily(g, id)) return "";
        PersonPage p = personView(TERMINAL, store, g, id);
        List<String> joined = FamilyLinks.about(store, g, id);
        if (!p.saysMore() && joined.isEmpty() && FamilyDoubts.about(store, g, id).isEmpty() && FamilyDoubts.readAsAbout(store, g, id).isEmpty()) return "";
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        out.println("  Names:");
        print(out, "    ", p.names());
        if (!p.addressed().isEmpty()) {
            out.println("  " + ADDRESSED_AS + ":");
            for (String a : p.addressed()) out.println("    " + a);
        }
        if (!p.families().isEmpty()) {
            out.println("  Families:");
            print(out, "    ", p.families());
        }
        if (!joined.isEmpty()) {
            out.println("  Joined from the evidence:");
            for (String j : joined) out.println("    " + j);
        }
        List<String> aside = FamilyDoubts.about(store, g, id);
        if (!aside.isEmpty()) {
            out.println("  " + FamilyDoubts.HEADING + ":");
            for (String a : aside) out.println("    " + a);
        }
        for (String a : FamilyDoubts.readAsAbout(store, g, id)) out.println("  " + FamilyDoubts.READ_AS_HEADING + ": " + a);
        for (FamilyNameQuestions.Question q : p.questions()) out.println("  A question for your family: " + q.text() + " The command researchzosho genealogy who asks it.");
        for (FamilyNameQuestions.Question q : p.notSettled()) out.println("  Not settled: " + q.text() + " " + heldBack(g, q));
        return buf.toString(StandardCharsets.UTF_8);
    }

    // ── a family ──────────────────────────────────────────────────────────────────────────────────────────────────────

    /** A family's page as it lists it: what is known of the family itself, its heads in order, its members. */
    private record FamilyView(List<String> about, List<Entry> heads, List<Entry> members) { }

    private static FamilyView familyView(Out o, Graph g, String id) {
        FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
        List<String> about = new ArrayList<>();
        List<String> aliases = FamilyHouses.aliasesOf(g, id);
        if (!aliases.isEmpty()) about.add(o.words("Also written: " + String.join(", ", aliases) + "."));
        String seat = FamilyHouses.seat(g, id);
        if (!seat.isBlank()) about.add(o.words("Its seat: " + seat + "."));
        String of = FamilyHouses.branchOf(g, id);
        if (of != null) about.add(o.words("It is a branch of ") + o.family(FamilyHouses.labelOf(g, of)) + o.words("."));
        List<String> branches = FamilyHouses.branches(g, id);
        if (!branches.isEmpty()) about.add(o.words(branches.size() == 1 ? "Its branch: " : "Its branches: ") + String.join(o.words(", "), branches.stream().map(b -> o.family(FamilyHouses.labelOf(g, b))).toList()) + o.words("."));
        String founder = FamilyHouses.founder(g, id);
        if (founder != null) about.add(o.words("Founded by ") + o.person(heading(g, g.node(founder) == null ? founder : g.node(founder).label()), g.node(founder) == null ? founder : g.node(founder).label()) + o.words("."));
        String hereditary = FamilyHouses.hereditaryName(g, id);
        if (!hereditary.isBlank()) about.add(o.words("Its heads carried the hereditary name " + hereditary + ", so one name here can be several people."));
        List<Entry> heads = new ArrayList<>();
        int n = 0;
        for (FamilyHouses.Membership m : FamilyHouses.heads(g, id)) {
            String label = labelOf(g, m.person());
            String years = m.from() == null && m.to() == null ? ", in years not known" : (m.from() == null ? "" : " from " + m.from().phrase()) + (m.to() == null ? "" : (m.from() == null ? " until " : " to ") + m.to().phrase()
                    + (m.toWorkedOut() ? " (the library worked out the year from when they came into their next family)" : ""));
            heads.add(new Entry((++n) + ". " + o.person(named(idx, g, m.person()), label) + o.words(", head" + years + ".") + o.claims(m.claims()), List.of()));
        }
        // each member once, with every membership they had in the family, in the order they came in
        Map<String, List<FamilyHouses.Membership>> byPerson = new LinkedHashMap<>();
        for (FamilyHouses.Membership m : asShown(FamilyHouses.members(g, id))) byPerson.computeIfAbsent(m.person(), k -> new ArrayList<>()).add(m);
        List<Entry> members = new ArrayList<>();
        for (Map.Entry<String, List<FamilyHouses.Membership>> e : byPerson.entrySet()) {
            String label = labelOf(g, e.getKey());
            List<String> parts = new ArrayList<>(), codes = new ArrayList<>();
            Set<String> elsewhere = new LinkedHashSet<>();
            for (FamilyHouses.Membership m : e.getValue()) {
                parts.add(membershipText(o, g, m));
                codes.addAll(m.claims());
                for (String x : List.of(m.cameFrom(), m.wentTo())) if (!x.isBlank() && !x.equals(id)) elsewhere.add(x);
            }
            List<String> under = new ArrayList<>();
            String c = personCommand(o, label, "shows this person's names and the families they belonged to.");
            if (c != null) under.add(c);
            for (String x : elsewhere) { String fc = familyCommand(o, FamilyHouses.labelOf(g, x), "shows that family."); if (fc != null) under.add(fc); }
            members.add(new Entry(o.person(named(idx, g, e.getKey()), label) + o.words(": ") + String.join(o.words("; "), parts) + o.words(".") + o.claims(codes), under));
        }
        return new FamilyView(about, heads, members);
    }

    private static String labelOf(Graph g, String id) { Graph.Node n = g.node(id); return n == null ? id : n.label(); }

    /** Somebody who bore a family name: their node, the name, and the years they bore it as far as anything says. */
    private record Bearer(String id, String name, String years, int first) { }

    /**
     * Everybody who bore a family name, with the name and the years they bore it: the "who bore a family name at a date" of the library made
     * visible. A person written only by that family name ("森田健二's father (written only as Endo)") is among them, not identified yet.
     */
    private static List<Bearer> bearers(Graph g, String family) {
        FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
        List<Bearer> out = new ArrayList<>();
        String want = FamilyHouses.familyName(family);
        if (want.isBlank()) return out;
        for (Graph.Node node : g.nodes()) {
            if (!"person".equals(node.kind())) continue;
            String[] mention = FamilyMentions.parts(node.label());
            if (mention != null) {
                if (FamilyForms.sameForm(mention[2], want) || mention[2].equalsIgnoreCase(want)) out.add(new Bearer(node.id(), mention[2], "written only by this family name; who it is is not known yet", Integer.MAX_VALUE));
                continue;
            }
            List<FamilyNameHistory.Name> ns = idx.names(node.id());
            long replacing = ns.stream().filter(FamilyNameHistory.Name::replaces).count();
            for (FamilyNameHistory.Name n : ns) {
                if (!bears(idx, node.id(), n, want)) continue;
                int[] y = idx.years(node.id(), n);
                String years = y[0] > 0 && y[1] > 0 ? "from " + y[0] + " to " + y[1] : y[0] > 0 ? "from " + y[0] : y[1] > 0 ? "until " + y[1] : "";
                FamilyHouses.Membership entered = years.isEmpty() && replacing == 1 && n.replaces() ? enteredLater(g, node.id(), want) : null;
                if (entered != null) {
                    // the only name they are known by, of a family they came into later: theirs from then, as the membership says
                    years = "from " + entered.from().phrase() + ", when they entered " + FamilyHouses.labelOf(g, entered.family());
                    y[0] = entered.from().year();
                } else if (years.isEmpty() && replacing == 1 && n.replaces()) {
                    // the only name they are known by: theirs for the life, as far as the life is dated
                    FamilyDate b = idx.born(node.id()), d = idx.died(node.id());
                    if (b != null) y[0] = b.year();
                    years = b != null && d != null ? "from their birth in " + b.phrase() + " to their death in " + d.phrase() : b != null ? "from their birth in " + b.phrase() : d != null ? "until their death in " + d.phrase() : "";
                }
                out.add(new Bearer(node.id(), n.written(), years.isEmpty() ? "in years not known" : years, y[0] > 0 ? y[0] : y[1] > 0 ? y[1] : Integer.MAX_VALUE - 1));
            }
        }
        out.sort(Comparator.comparingInt(Bearer::first).thenComparing(b -> named(idx, g, b.id())));
        return out;
    }

    /** A dated membership by which somebody came into a family of this name by adoption, as 婿養子, by 入夫 or by marriage; null when none. */
    private static FamilyHouses.Membership enteredLater(Graph g, String id, String family) {
        for (FamilyHouses.Membership m : FamilyHouses.families(g, id))
            if (m.from() != null && (FamilyHouses.ADOPTED_IN.contains(m.how()) || m.how().equals("marriage")) && FamilyForms.sameForm(FamilyHouses.nameOf(FamilyHouses.labelOf(g, m.family())), family)) return m;
        return null;
    }

    private static boolean bears(FamilyNameHistory.Index idx, String id, FamilyNameHistory.Name n, String family) {
        if (n.kind().equals("hereditary")) return false;
        if (n.hasFamily(family)) return true;
        String part = n.family().isBlank() ? idx.parts(id, n.written())[0] : "";
        return !part.isBlank() && FamilyForms.sameForm(part, family);
    }

    /**
     * {@code genealogy family [<family or family name>]}: with nothing, the families the library holds; with a family's label, its page; with
     * a family name alone, every family of that name and the people who bore it. Returns the exit code.
     */
    public static int cliFamily(LibraryStore store, String family, PrintStream out) throws IOException {
        Graph g = FamilyPeople.view(store);
        List<String> all = FamilyHouses.all(g);
        if (family == null || family.isBlank()) {
            if (all.isEmpty()) { out.println("Your library holds no family as a family yet. A family is written down when a text you read speaks of it as a family, such as the Morita family or 森田家."); return 0; }
            out.println("The families in your library:");
            for (String f : all) {
                int members = (int) FamilyHouses.members(g, f).stream().map(FamilyHouses.Membership::person).distinct().count();
                out.println("  " + FamilyHouses.labelOf(g, f) + ", with " + (members == 0 ? "no member written down yet" : members == 1 ? "1 member" : members + " members"));
            }
            String first = FamilyHouses.labelOf(g, all.get(0));
            out.println("\nTo open one, give its name as it is written here. For example, researchzosho genealogy family " + shellQuoted(first) + " shows the heads and members of " + first + ".");
            out.println("A family name alone, such as researchzosho genealogy family " + shellQuoted(FamilyHouses.nameOf(first)) + ", lists every family of that name and everybody who bore it.");
            return 0;
        }
        String id = opened(g, family);
        if (id != null) { printFamily(out, g, id); return 0; }
        // a family name alone, or words that fit more than one family of a name: every family of that name, and everybody who bore it
        String name = FamilyHouses.familyName(family);
        List<String> named = FamilyHouses.named(g, family);
        List<Bearer> bore = bearers(g, family);
        if (named.isEmpty() && bore.isEmpty()) {
            String person = find(g, family);
            out.println("Your library holds no family called \"" + family + "\", and nobody in it bore that family name."
                    + (person != null && !FamilyHouses.isFamily(g, person) ? " " + labelOf(g, person) + " is a person: the command " + personCommand(TERMINAL, labelOf(g, person), "shows the families they belonged to.") : " The command researchzosho genealogy family, with nothing after it, lists the families your library holds."));
            return 1;
        }
        if (named.isEmpty()) out.println("Your library holds no " + name + " family as a family of its own yet.");
        else {
            out.println(named.size() == 1 ? "The family called " + name + ":" : "The " + named.size() + " families called " + name + ". A family name is not a family: families of one name are told apart by their seat.");
            for (String f : named) {
                String seat = FamilyHouses.seat(g, f), label = FamilyHouses.labelOf(g, f);
                out.println("  " + label + (seat.isBlank() ? ", whose seat is not written down" : ", with its seat in " + seat));
                out.println("    " + familyCommand(TERMINAL, label, "shows its heads and its members."));
            }
        }
        if (!bore.isEmpty()) {
            out.println("\nThe people who bore the family name " + name + ", and the years they bore it:");
            FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
            for (Bearer b : bore) out.println("  " + named(idx, g, b.id()) + ": as " + b.name() + ", " + b.years() + ".");
            // the command takes the label, which is how the library finds the person; the sentence names them as the list above does
            String example = labelOf(g, bore.get(0).id());
            out.println("A person who took the name later bore it only from then on. The command researchzosho genealogy names " + shellQuoted(example) + " shows all the names and families of " + named(idx, g, bore.get(0).id()) + "; give it any person's name as your library writes it.");
        }
        return 0;
    }

    /**
     * The family whose page typed words open: the family's label, in its modern characters too; any other words the family is written with
     * (遠藤家, the Endō family) only when no other family has the family name they give, because a family name never picks one of two
     * families. A branch and the family it is a branch of are told apart by their words (森田分家, 森田家), so the one does not count against
     * the other. Null when the words open no family's page.
     */
    static String opened(Graph g, String words) {
        if (words == null || words.isBlank()) return null;
        String id = g.nodeIdOf(words);
        if (!FamilyHouses.isFamily(g, id)) id = g.nodeIdOf(KanjiForms.modern(words));
        if (!FamilyHouses.isFamily(g, id)) return null;
        String label = FamilyHouses.labelOf(g, id);
        if (Vocabulary.norm(KanjiForms.modern(label)).equals(Vocabulary.norm(KanjiForms.modern(words.strip())))) return id;
        String self = id;
        boolean branch = FamilyHouses.isBranch(g, self);
        long others = FamilyHouses.named(g, words).stream().filter(o -> !o.equals(self) && FamilyHouses.isBranch(g, o) == branch).count();
        return others > 0 ? null : id;
    }

    private static void printFamily(PrintStream out, Graph g, String id) {
        FamilyView v = familyView(TERMINAL, g, id);
        out.println(FamilyHouses.labelOf(g, id));
        for (String a : v.about()) out.println(a);
        out.println("\nHeads, in order:");
        if (v.heads().isEmpty()) out.println("  No head of this family is written down.");
        else print(out, "  ", v.heads());
        out.println("\nMembers, in the order they came in:");
        if (v.members().isEmpty()) out.println("  No member of this family is written down yet.");
        else print(out, "  ", v.members());
        out.println("\nA family name alone, such as researchzosho genealogy family " + shellQuoted(FamilyHouses.nameOf(FamilyHouses.labelOf(g, id))) + ", lists every family of that name and everybody who bore it.");
    }

    // ── the web pages ─────────────────────────────────────────────────────────────────────────────────────────────────

    private static void list(StringBuilder b, List<Entry> entries, boolean ordered) {
        b.append(ordered ? "<ol>" : "<ul>");
        for (Entry e : entries) {
            b.append("<li>").append(e.text());
            if (!e.under().isEmpty()) b.append("<br><span class=\"k\">").append(String.join("<br>", e.under())).append("</span>");
            b.append("</li>");
        }
        b.append(ordered ? "</ol>" : "</ul>");
    }

    /**
     * /person?name=…: the person's page, for anybody who may read the library. Links lead to their families, the tree and each claim, and,
     * for a person who may write the library, to the questions about the person on Who is who.
     */
    public static String personPage(LibraryStore store, Patrons.Patron patron, String name) throws IOException {
        Graph g = FamilyPeople.view(store);
        String id = find(g, name);
        String ask = "<form method=\"get\" action=\"/person\" style=\"display:flex;gap:.5em;margin:.4em 0 .8em\"><input name=\"name\" value=\"" + FamilyTree.esc(name == null ? "" : name) + "\" placeholder=\"a person in your family\" style=\"flex:1;font:inherit;padding:.4em .6em\"> <button>Show</button></form>";
        if (id == null) return ask + "<p>" + (name == null || name.isBlank() ? "Type the name of a person to see their names and the families they belonged to." : "Nobody of that name is in your library.") + " <a href=\"/tree\">The family tree</a> lists the people who have family claims.</p>";
        String label = g.node(id).label();
        if (FamilyHouses.isFamily(g, id)) return ask + "<p>" + WEB.family(label) + " is a family, not a person.</p>";
        FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
        String heading = named(idx, g, id);
        PersonPage p = personView(WEB, store, g, id);
        StringBuilder b = new StringBuilder(ask).append("<h2>").append(FamilyTree.esc(heading)).append("</h2>");
        if (!shown(idx, g, id).equals(label)) b.append("<p class=\"k\">Your library files this person under ").append(FamilyTree.esc(label)).append(".</p>");
        b.append("<h3>Names, in the order of the life</h3>");
        list(b, p.names(), false);
        if (!p.addressed().isEmpty()) {
            b.append("<h3>").append(ADDRESSED_AS).append("</h3><p class=\"k\">How a text addresses this person; no name of theirs.</p><ul>");
            for (String a : p.addressed()) b.append("<li>").append(a).append("</li>");
            b.append("</ul>");
        }
        b.append("<h3>Families</h3>");
        if (p.families().isEmpty()) b.append("<p>No family is written down for this person. A family is written down when a text you read speaks of it as a family, such as the Morita family or 森田家.</p>");
        else list(b, p.families(), false);
        List<String> joined = FamilyLinks.about(store, g, id);
        if (!joined.isEmpty()) {
            b.append("<h3>Joined from the evidence</h3><ul>");
            for (String j : joined) b.append("<li>").append(FamilyTree.esc(j)).append("</li>");
            b.append("</ul><p class=\"k\">").append(FamilyTree.esc(FamilyLinks.TAKE_BACK)).append("</p>");
        }
        List<String> aside = FamilyDoubts.about(store, g, id);
        if (!aside.isEmpty()) {
            b.append("<h3>").append(FamilyTree.esc(FamilyDoubts.HEADING)).append("</h3><ul>");
            for (String a : aside) b.append("<li>").append(FamilyTree.esc(a)).append("</li>");
            b.append("</ul><p class=\"k\">").append(FamilyTree.esc(FamilyDoubts.WHAT)).append("</p>");
        }
        List<String> readAs = FamilyDoubts.readAsAbout(store, g, id);
        if (!readAs.isEmpty()) {
            b.append("<h3>").append(FamilyTree.esc(FamilyDoubts.READ_AS_HEADING)).append("</h3><ul>");
            for (String a : readAs) b.append("<li>").append(FamilyTree.esc(a)).append("</li>");
            b.append("</ul>");
        }
        if (!p.questions().isEmpty()) {
            b.append("<h3>Questions for your family</h3><ul>");
            for (FamilyNameQuestions.Question q : p.questions()) b.append("<li>").append(FamilyTree.esc(q.text())).append("</li>");
            // Who is who takes the family's word, so only a person who may write the library is sent there, to this person's first question
            if (Pages.mayWrite(store, patron)) b.append("</ul><p><a href=\"/who?show=names-").append(enc(p.questions().get(0).code())).append("\">Answer them on Who is who</a>, where each answer says what it will do.</p>");
            else b.append("</ul><p>The owner of the library, and anyone the owner lets write to it, answer these on the page Who is who.</p>");
        }
        if (!p.notSettled().isEmpty()) {
            b.append("<h3>Not settled</h3><p>").append(FamilyTree.esc(NOT_SETTLED.replaceFirst("^Not settled, and a", "A"))).append("</p><ul>");
            for (FamilyNameQuestions.Question q : p.notSettled()) b.append("<li>").append(FamilyTree.esc(q.text() + " " + heldBack(g, q))).append("</li>");
            b.append("</ul>");
        }
        b.append("<p><a href=\"/tree?focus=").append(enc(label)).append("\">The family tree around ").append(FamilyTree.esc(heading)).append("</a> · <a href=\"/family\">Every family in your library</a> · <a href=\"/summary\">The summary of your family</a></p>");
        return b.toString();
    }

    /** /family?name=… (a family's page) or ?surname=… (the families of a name and who bore it), for anybody who may read the library. */
    public static String familyPage(LibraryStore store, Patrons.Patron patron, String name, String surname) throws IOException {
        Graph g = FamilyPeople.view(store);
        String id = opened(g, name);
        // words that fit more than one family of a name (遠藤家 for two 遠藤 families): the families of that name, as the name alone lists them
        if (id == null && name != null && !name.isBlank() && (surname == null || surname.isBlank()) && FamilyHouses.named(g, name).size() > 1) surname = name;
        String ask = "<form method=\"get\" action=\"/family\" style=\"display:flex;gap:.5em;margin:.4em 0 .8em\"><input name=\"surname\" value=\"" + FamilyTree.esc(surname == null ? "" : surname) + "\" placeholder=\"a family name\" style=\"flex:1;font:inherit;padding:.4em .6em\"> <button>Show</button></form>";
        StringBuilder b = new StringBuilder();
        if (id != null) {
            FamilyView v = familyView(WEB, g, id);
            String label = FamilyHouses.labelOf(g, id);
            b.append("<h2>").append(FamilyTree.esc(label)).append("</h2>");
            for (String a : v.about()) b.append("<p>").append(a).append("</p>");
            b.append("<h3>Heads, in order</h3>");
            if (v.heads().isEmpty()) b.append("<p>No head of this family is written down.</p>");
            else { b.append("<ul>"); for (Entry e : v.heads()) b.append("<li>").append(e.text()).append("</li>"); b.append("</ul>"); }
            b.append("<h3>Members, in the order they came in</h3>");
            if (v.members().isEmpty()) b.append("<p>No member of this family is written down yet.</p>");
            else list(b, v.members(), false);
            String fn = FamilyHouses.nameOf(label);
            return b.append("<p><a href=\"/family?surname=").append(enc(fn)).append("\">Every family called ").append(FamilyTree.esc(fn)).append(", and everybody who bore the name</a> · <a href=\"/family\">Every family in your library</a> · <a href=\"/summary\">The summary of your family</a></p>").toString();
        }
        b.append(ask);
        if (name != null && !name.isBlank() && (surname == null || surname.isBlank())) b.append("<p>Your library holds no family called ").append(FamilyTree.esc(name)).append(".</p>");
        if (surname == null || surname.isBlank()) {
            List<String> families = FamilyHouses.all(g);
            if (families.isEmpty()) return b.append("<p>No family is written down in your library yet. A family is written down when a text you read speaks of it as a family, such as the Morita family or 森田家.</p>").toString();
            b.append("<p>The families in your library. Type a family name above to see every family of that name and everybody who bore it.</p><ul>");
            for (String f : families) b.append("<li>").append(WEB.family(FamilyHouses.labelOf(g, f))).append("</li>");
            return b.append("</ul>").toString();
        }
        String fn = FamilyHouses.familyName(surname);
        List<String> named = FamilyHouses.named(g, surname);
        List<Bearer> bore = bearers(g, surname);
        if (named.isEmpty()) b.append("<p>Your library holds no ").append(FamilyTree.esc(fn)).append(" family as a family of its own yet.</p>");
        else {
            b.append("<h2>").append(named.size() == 1 ? "The family called " : "The " + named.size() + " families called ").append(FamilyTree.esc(fn)).append("</h2>");
            if (named.size() > 1) b.append("<p class=\"k\">A family name is not a family: families of one name are told apart by their seat.</p>");
            b.append("<ul>");
            for (String f : named) { String seat = FamilyHouses.seat(g, f); b.append("<li>").append(WEB.family(FamilyHouses.labelOf(g, f))).append(FamilyTree.esc(seat.isBlank() ? ", whose seat is not written down" : ", with its seat in " + seat)).append("</li>"); }
            b.append("</ul>");
        }
        if (bore.isEmpty()) return b.append("<p>Nobody in your library bore the family name ").append(FamilyTree.esc(fn)).append(".</p>").toString();
        FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
        b.append("<h3>The people who bore the family name ").append(FamilyTree.esc(fn)).append(", and the years they bore it</h3><ul>");
        for (Bearer x : bore) b.append("<li>").append(WEB.person(named(idx, g, x.id()), labelOf(g, x.id()))).append(FamilyTree.esc(": as " + x.name() + ", " + x.years() + ".")).append("</li>");
        return b.append("</ul><p class=\"k\">A person who took the name later bore it only from then on.</p>").toString();
    }

    static String enc(String s) { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8); }
}
