package org.researchzosho.librarian;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Families as things: a house, a line, a clan, a 家, a branch. A family is a node of kind {@code family}, described in nodes.md, never a
 * person, and a family name is not a family: two unrelated 遠藤 families are two families, told apart by their seats. A person's
 * MEMBERSHIP is a claim ({@code <person> | member-of | <family>}) with a {@link FamilyDetail} reading: how they came in (birth, marriage,
 * adoption, 婿養子, 入夫, succession, founding) and how they left, from and to, and their role (head, heir, member). A family's own facts are
 * claims about the family node: its seat, the family it is a branch of, its founder, the hereditary name of its heads.
 *
 * <p>Nobody is made a member because they share a surname, and a family name is never turned into a person. A branch (森田分家) is a family
 * of its own under the same family name; the main house (森田本家) is the family itself. What is worked out here, from the claims each time:
 * the members and their years, the heads in order, the branches both ways, and where each member came from and went to, in the order of
 * each life, which the person's names give where the claims give no years.
 */
public final class FamilyHouses {

    private FamilyHouses() { }

    /** The kind of a family's node. */
    public static final String KIND = "family";
    public static final String MEMBER = "member-of", SEAT = "family-seat", BRANCH = "branch-of", FOUNDED = "founded-by";

    /** The ways into a family that take a person out of the family they were in: an adoption, as 婿養子 or not, and 入夫 marriage. */
    static final Set<String> ADOPTED_IN = Set.of("adoption", "mukoyoshi", "nyufu");

    /** The relations whose subject is a family, not a person. */
    public static final Set<String> OF_A_FAMILY = Set.of(SEAT, BRANCH, FOUNDED);

    /**
     * One person's membership of one family. {@code how}/{@code left}: how they came in and how they left, as the claims say ("" where they
     * do not). {@code cameFrom}/{@code wentTo}: the family ids of their membership before and after this one in the order of their life (by
     * the years the claims give; a membership whose years they do not give is placed by the person's names: in the family of the first name,
     * or of a birth parent then, at the birth; in the family of a later name, at the year it was taken); for somebody with no earlier
     * membership, the family a birth parent belonged to at the birth, and then {@code workedOut}. {@code claims}: the claims that say it.
     * The membership of the family a birth parent belonged to then begins at the birth: a claim that gives no start does not date it by its
     * own date. Another family of the first name's family part keeps the year its claim gives. When no claim says when they left it and they next came into another family by an
     * adoption (as 婿養子, as heir, or by 入夫 marriage), {@code to} is worked out as the year that membership began, and {@code toWorkedOut}.
     * A marriage into another family leaves the family they were born into open: in many places a person stays in it for life.
     */
    public record Membership(String person, String family, String how, String left, FamilyDate from, FamilyDate to, String role, String event, String eventOut,
                             List<String> claims, String cameFrom, String wentTo, boolean workedOut, boolean toWorkedOut) {
        public Membership(String person, String family, String how, String left, FamilyDate from, FamilyDate to, String role, String event, String eventOut,
                          List<String> claims, String cameFrom, String wentTo, boolean workedOut) {
            this(person, family, how, left, from, to, role, event, eventOut, claims, cameFrom, wentTo, workedOut, false);
        }
        /** Whether the membership held in a year, as far as its years say: a membership whose years are not given holds. */
        public boolean holds(int year) { return (from == null || from.year() <= year) && (to == null || year < to.year()); }
    }

    // ── the words for a family ────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Words that say "family" by themselves, and only beside a name: 森田家, 森田一族, 森田本家 and 森田分家, a 문중 after a name, the Morita
     * family, the Hale clan, the Hart dynasty. "The family" alone names no family, and neither does "the house" or "the line". The words are
     * defaults behind the rule that a family is kept only when the words name it as a family.
     */
    static final Pattern FAMILY_WORD = Pattern.compile("(?<=\\p{IsHan})(?:家(?![族具屋政督])|一家|一族|一門|本家|分家|宗家|氏族|宗族)|(?<=[\\p{IsHan}\\p{IsHangul}])\\s?문중"
            + "|(?<![\\p{L}'’-])(\\p{Lu}[\\p{L}'’-]*|[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]+)\\s+(?:(?:branch|main|cadet|senior|junior)\\s+)?(?i:family|clan|dynasty)\\b");

    /**
     * Words in characters that end in 家 and name a trade, a nation or a kind of household, not a family: 作家 is a writer, 国家 a nation, 実家
     * the home one was born in. They are defaults behind the rule that 家 speaks of a family only beside a name.
     */
    private static final Set<String> NOT_A_FAMILY = Set.of("国家", "作家", "画家", "書家", "農家", "商家", "武家", "公家", "実家", "生家", "婚家", "養家", "大家",
            "良家", "名家", "旧家", "一家", "本家", "分家", "宗家", "専門家", "研究家", "政治家", "音楽家", "建築家", "小説家", "実業家", "芸術家", "評論家",
            "作曲家", "写真家", "漫画家", "資本家", "企業家", "事業家", "思想家", "活動家", "宗教家", "教育家", "愛好家", "収集家", "登山家", "落語家",
            "陶芸家", "書道家", "茶道家", "華道家", "演出家", "脚本家", "劇作家", "翻訳家", "冒険家", "探検家", "発明家", "銀行家", "美食家", "蒐集家");

    /** Whether characters that end in a word for a family are one of the words that are no family ({@link #NOT_A_FAMILY}). */
    private static boolean notAFamily(String han) {
        for (String n : NOT_A_FAMILY) if (han.endsWith(n) && (han.length() == n.length() || n.length() >= 3)) return true;
        return false;
    }

    /**
     * Words that name a family only when the name beside them is a family name: "the house of Hale" (and the House of Commons), "the Hales"
     * (and the Quakers), "the Morita line" (and a railway line), "the Morita house". Each gives its name in group 1, 2, 3 or 4.
     */
    private static final Pattern FAMILY_WORD_IF_NAMED = Pattern.compile("(?i:\\bhouse\\s+of\\s+)(?:the\\s+)?(\\p{Lu}[\\p{L}'’-]*)"
            + "|\\b[Tt]he\\s+(\\p{Lu}[\\p{L}'’-]*s)(?![\\p{L}'’-])"
            + "|(?<![\\p{L}'’-])(\\p{Lu}[\\p{L}'’-]*)\\s+(?i:line)\\b"
            + "|(?<![\\p{L}'’-])(\\p{Lu}[\\p{L}'’-]*)\\s+(?i:house)\\b");

    /** Words that stand before "family" and are no name: an article, a pronoun, a word for a kind of family ("the Royal family"). */
    private static final Set<String> NO_NAME = Set.of("the", "a", "an", "his", "her", "their", "our", "my", "your", "its", "this", "that", "these", "those",
            "whose", "which", "each", "every", "no", "one", "some", "any", "same", "other", "whole", "entire", "extended", "immediate", "host", "royal",
            "imperial", "holy", "noble", "new", "old", "first", "second", "main", "branch", "cadet", "senior", "junior", "head");

    /**
     * Whether words speak of a family as a family by themselves ({@link #FAMILY_WORD}): a word for a family beside a name, as in 森田家, 森田分家,
     * "the Morita family", "Endo's son entered the Morita family". "The House of Commons", "the Quakers", "the books" and "the family" are
     * not: {@link #familyWord(String, Collection)} takes them when the name in them is one the library knows as a family name.
     */
    public static boolean familyWord(String words) {
        if (words == null) return false;
        Matcher m = FAMILY_WORD.matcher(words);
        while (m.find()) {
            if (m.group(1) != null) { if (!NO_NAME.contains(m.group(1).toLowerCase(Locale.ROOT))) return true; continue; }
            // characters: the name before the word, as far as the characters run, and never a word that only ends in 家 (作家, 国家, 分家)
            int from = m.start();
            while (from > 0 && Character.UnicodeScript.of(words.codePointBefore(from)) == Character.UnicodeScript.HAN) from = words.offsetByCodePoints(from, -1);
            String run = words.substring(from, m.end()).strip();
            if (!notAFamily(run)) return true;
        }
        return false;
    }

    /**
     * Whether words speak of a family as a family: by themselves ({@link #familyWord(String)}), or in a form that names other things too
     * ("the house of Hale", "the Hales", "the Morita line") whose name is one of {@code familyNames} in one of its forms (Endō, Endo).
     */
    public static boolean familyWord(String words, Collection<String> familyNames) {
        if (familyWord(words)) return true;
        if (words == null || familyNames == null || familyNames.isEmpty()) return false;
        Matcher m = FAMILY_WORD_IF_NAMED.matcher(words);
        while (m.find()) {
            String name = m.group(1) != null ? m.group(1) : m.group(2) != null ? plural(m.group(2)) : m.group(3) != null ? m.group(3) : m.group(4);
            if (name == null || name.isBlank() || NO_NAME.contains(name.toLowerCase(Locale.ROOT))) continue;
            for (String k : familyNames) if (k != null && !k.isBlank() && (FamilyForms.sameForm(k.strip(), name) || k.strip().equalsIgnoreCase(name))) return true;
        }
        return false;
    }

    private static final Pattern BRACKET = Pattern.compile("(?:\\s*[(（][^)）]*[)）])+\\s*$");
    private static final List<String> HAN_WORDS = List.of("本家", "分家", "宗家", "一家", "一族", "一門", "氏族", "宗族", "家");
    private static final Pattern HOUSE_OF = Pattern.compile("(?i)^(?:the\\s+)?house\\s+of\\s+(?:the\\s+)?(.+)$");
    private static final Pattern NAME_FAMILY = Pattern.compile("(?i)^(?:(?:the|a|an)\\s+)?(.+?)\\s+(?:(?:branch|main|cadet|senior|junior)\\s+)?(?:family|house|line|clan|dynasty)(?:\\s+(?:of|from|in|at)\\s+.+)?$");
    private static final Pattern THE_NAMES = Pattern.compile("^[Tt]he\\s+(\\p{Lu}[\\p{L}'’-]*s)(?:\\s+(?:of|from|in|at)\\s+.+)?$");

    /**
     * The name that tells which family the words speak of: 森田家 is 森田, and so are 森田本家 and 遠藤宗家 (the main house, which is the family
     * itself); "the Morita family" is Morita, "the house of Hale" Hale, "the Hales" Hale, "the Ellises" Ellis, a family's label "遠藤 family
     * (広島県安芸郡)" 遠藤. A branch is a family of its own, so its words are kept whole: 森田分家 is 森田分家, the family of its own words, whose
     * family name is 森田 ({@link #nameOf}) and whose kind {@link #branchKind} says. "" when the words for a family stand beside no name ("the
     * family", "a railway line"); the words as they are when they carry no word for a family (遠藤, Morita).
     */
    public static String familyName(String written) {
        String w = written == null ? "" : BRACKET.matcher(written.strip()).replaceAll("").strip();
        if (branchKind(w).equals("branch") && !rootName(w).isBlank()) return w.replaceFirst("(?i)^(?:the|a|an)\\s+", "").strip();
        return rootName(w);
    }

    /** The family name the words give, whatever house of the family they speak of: 森田家, 森田本家 and 森田分家 are all 森田. */
    private static String rootName(String written) {
        String w = written == null ? "" : BRACKET.matcher(written.strip()).replaceAll("").strip();
        Matcher m;
        if (w.matches("本家|分家|宗家|一家|一族|一門|氏族|宗族|家")) return "";
        String[] han = hanSplit(w);
        if (han != null) return han[0];
        if ((m = HOUSE_OF.matcher(w)).matches()) return aName(m.group(1));
        if ((m = NAME_FAMILY.matcher(w)).matches()) return aName(m.group(1));
        if ((m = THE_NAMES.matcher(w)).matches()) return plural(m.group(1));
        // words that begin with an article and speak of no family are no family name: "the books", "a house"
        if (w.matches("(?i)^(?:the|a|an)\\s.*")) return "";
        return w;
    }

    /** A name as it stands beside a word for a family; "" when it is no name: an article or a pronoun, or Latin letters with no capital. */
    private static String aName(String s) {
        String n = s == null ? "" : s.strip();
        if (n.isEmpty() || NO_NAME.contains(n.toLowerCase(Locale.ROOT))) return "";
        if (FamilyForms.script(n).equals("latin") && !n.matches(".*\\p{Lu}.*")) return "";
        return n;
    }

    /**
     * A family written in characters, split into its name and its word for a family, {name, word}: the longest word that leaves a name of at
     * least two characters, so 森田本家 is 森田 and 本家 while a two-character name that ends in 本 or 分 and is followed by 家 is that name
     * and 家; else the longest word, for a name of one character. Null when the words are not a name of up to four characters and such a word.
     */
    private static String[] hanSplit(String w) {
        if (w == null || !w.matches("\\p{IsHan}{2,}") || notAFamily(w)) return null;
        String[] best = null;
        for (String word : HAN_WORDS) {
            if (!w.endsWith(word) || w.length() <= word.length() || w.length() - word.length() > 4) continue;
            String name = w.substring(0, w.length() - word.length());
            boolean whole = name.length() >= 2, bestWhole = best != null && best[0].length() >= 2;
            if (best == null || whole && !bestWhole || whole == bestWhole && word.length() > best[1].length()) best = new String[]{name, word};
        }
        return best;
    }

    /** The family name in an English plural: the Hales is Hale, the Lees Lee, the Endos Endo; -es only after s, x, z, ch and sh (the Ellises). */
    private static String plural(String names) {
        if (!names.endsWith("s") || names.length() < 3) return names;
        String stem = names.substring(0, names.length() - 1);
        if (stem.endsWith("e") && stem.substring(0, stem.length() - 1).matches(".*(?:s|x|z|ch|sh)")) return stem.substring(0, stem.length() - 1);
        return stem;
    }

    /**
     * Whether the words speak of the main house of a family ("main": 森田本家, 遠藤宗家, the Morita main family) or of a branch of it
     * ("branch": 森田分家, the Morita branch family, a cadet line); "" when they say neither. The family name is the same ({@link #nameOf});
     * a branch is a family of its own, and the main house is the family itself.
     */
    public static String branchKind(String written) {
        String w = written == null ? "" : BRACKET.matcher(written.strip()).replaceAll("").strip();
        String[] han = hanSplit(w);
        if (han != null) return han[1].equals("分家") ? "branch" : han[1].equals("本家") || han[1].equals("宗家") ? "main" : "";
        if (w.matches("(?i).*\\b(?:branch|cadet)\\s+(?:family|house|line)\\b.*") || w.matches("(?i).*\\bcadet\\s+branch\\b.*")) return "branch";
        if (w.matches("(?i).*\\b(?:main|senior)\\s+(?:family|house|line)\\b.*")) return "main";
        return "";
    }

    /** The family name in a family's label, or in the words for a family: "森田 family (広島)", "森田 branch family" and 森田分家 are all 森田. */
    public static String nameOf(String label) { return rootName(label); }

    /**
     * A family's label: {@code <name> family}, or {@code <name> branch family} for a branch ({@link #branchKind}), with the seat in brackets
     * when it is known, or the first member when another family has the name.
     */
    public static String label(String name, String seat, String firstMember) { return label(rootName(name), branchKind(name).equals("branch"), seat, firstMember); }

    private static String label(String familyName, boolean branch, String seat, String firstMember) {
        String n = familyName + (branch ? " branch family" : " family");
        if (seat != null && !seat.isBlank()) return n + " (" + seat.strip() + ")";
        if (firstMember != null && !firstMember.isBlank()) return n + " (of " + firstMember.strip() + ")";
        return n;
    }

    // ── the families of a library ─────────────────────────────────────────────────────────────────────────────────────

    /** Every family of the graph by id: those nodes.md describes as families, with a claim or without. */
    public static List<String> all(Graph g) {
        Set<String> out = new LinkedHashSet<>();
        for (Vocabulary.Term t : g.curated().terms().values()) if (KIND.equals(Graph.kindOf(t.description()))) out.add(g.nodeIdOf(t.slug()));
        for (Graph.Node n : g.nodes()) if (KIND.equals(n.kind())) out.add(n.id());
        return new ArrayList<>(out);
    }

    /** Whether a node is a family. */
    public static boolean isFamily(Graph g, String id) {
        Graph.Node n = g.node(id);
        if (n != null) return KIND.equals(n.kind());
        Vocabulary.Term t = g.curated().get(id);
        return t != null && KIND.equals(Graph.kindOf(t.description()));
    }

    /** A family's label, as nodes.md writes it. */
    public static String labelOf(Graph g, String id) {
        Graph.Node n = g.node(id);
        if (n != null) return n.label();
        Vocabulary.Term t = g.curated().get(id);
        return t == null ? id : Graph.labelOf(t.description()).isBlank() ? id : Graph.labelOf(t.description());
    }

    /** A family's other names as nodes.md writes them. */
    static List<String> aliasesOf(Graph g, String id) {
        Graph.Node n = g.node(id);
        if (n != null) return n.aliases();
        Vocabulary.Term t = g.curated().get(id);
        return t == null ? List.of() : t.also();
    }

    /**
     * The families whose name, in any form the library has for it, is this family name (遠藤, Endō and Endo alike when the family has those
     * forms): every family of the name, its branches too; for words that speak of a branch (森田分家) only the branches, and for words that
     * speak of the main house (森田本家) only the others. A family whose name has a form in characters or kana is a Japanese family: its forms
     * in Latin letters are romaji, and every romaji spelling of a long vowel finds it (Endo, Endō, Endou, Endoh for the 遠藤 family).
     */
    public static List<String> named(Graph g, String name) {
        String want = rootName(name), kind = branchKind(name);
        List<String> out = new ArrayList<>();
        if (want.isBlank()) return out;
        for (String id : all(g)) {
            if (kind.equals("branch") && !isBranch(g, id) || kind.equals("main") && isBranch(g, id)) continue;
            List<String> forms = new ArrayList<>(List.of(labelOf(g, id)));
            forms.addAll(aliasesOf(g, id));
            boolean romaji = forms.stream().anyMatch(f -> FamilyForms.japanese(rootName(f)));
            for (String f : forms) if (FamilyForms.sameForm(rootName(f), want, romaji)) { out.add(id); break; }
        }
        return out;
    }

    /** A family's seat: what a family-seat claim names, else the place in its label's brackets; "" when neither says. */
    public static String seat(Graph g, String id) {
        for (Graph.Edge e : g.edges()) if (e.from().equals(id) && e.predicate().equals(SEAT) && !FamilyKin.gone(e)) { Graph.Node p = g.node(e.to()); return p == null ? e.to() : p.label(); }
        Matcher m = Pattern.compile("[(（]([^)）]*)[)）]\\s*$").matcher(labelOf(g, id));
        return m.find() && !m.group(1).strip().startsWith("of ") && !m.group(1).strip().matches("\\d+") ? m.group(1).strip() : "";
    }

    private static boolean sameSeat(String a, String b) {
        String x = Vocabulary.norm(KanjiForms.modern(a)), y = Vocabulary.norm(KanjiForms.modern(b));
        return x.equals(y) || Graph.samePlace(a, b);
    }

    /** Whether a family is a branch of a family of its name: its label or a way a source wrote it says so (森田分家, the Morita branch family). */
    public static boolean isBranch(Graph g, String id) {
        if (branchKind(labelOf(g, id)).equals("branch")) return true;
        for (String a : aliasesOf(g, id)) if (branchKind(a).equals("branch")) return true;
        return false;
    }

    /**
     * Whether two families are known to be two: one is a branch of the other, one is a branch and the other is not, or each has a seat and
     * the seats are different places. Two families of one name that are not told apart this way may be one, and only the family can say.
     */
    public static boolean toldApart(Graph g, String a, String b) {
        if (a == null || b == null || a.equals(b)) return false;
        if (b.equals(branchOf(g, a)) || a.equals(branchOf(g, b)) || isBranch(g, a) != isBranch(g, b)) return true;
        String sa = seat(g, a), sb = seat(g, b);
        return !sa.isBlank() && !sb.isBlank() && !sameSeat(sa, sb);
    }

    /**
     * The family of this name and seat: a family whose name is this one and whose seat is the same place; with no seat to choose by, the
     * only family of this name. A branch (森田分家) is found only among the branches of the name, the family or its main house (森田家,
     * 森田本家) only among the others. Null when there is none, or when several could be meant: a family name alone never picks one of two
     * families, and a seat never joins a family whose seat is not written down, which may be another family of the name (the family is then
     * asked which it is).
     */
    public static String find(Graph g, String name, String seat) { return find(g, name, seat, branchKind(name)); }

    private static String find(Graph g, String name, String seat, String kind) {
        boolean branch = kind.equals("branch");
        List<String> same = named(g, rootName(name)).stream().filter(id -> isBranch(g, id) == branch).toList();
        if (same.isEmpty()) return null;
        if (seat != null && !seat.isBlank()) {
            List<String> there = same.stream().filter(id -> !seat(g, id).isBlank() && sameSeat(seat(g, id), seat)).toList();
            return there.size() == 1 ? there.get(0) : null;
        }
        return same.size() == 1 ? same.get(0) : null;
    }

    /** {@link #family(LibraryStore, Graph, String, String, String, List)} with no written forms beside the name. */
    public static String family(LibraryStore store, Graph g, String name, String seat, String firstMember) throws IOException {
        return family(store, g, name, seat, firstMember, List.of());
    }

    /**
     * The family of this name and seat, found or made; its label. Made as {@code <name> family} ({@code <name> branch family} for a branch,
     * {@link #branchKind}), with the seat in brackets when one is known, or with its first member when another family of the name exists and
     * no seat tells them apart (the question which of them is meant is then the family's to answer). {@code written}: the ways the source
     * wrote the family (森田家, the Morita family), kept as its other names where they lead to no other entry. {@code g}: the graph as it
     * stood before; a family made here is not in it.
     */
    public static String family(LibraryStore store, Graph g, String name, String seat, String firstMember, List<String> written) throws IOException {
        String n = rootName(name);
        if (n.isBlank()) return null;
        String kind = branchKind(name);
        if (kind.isEmpty() && written != null) for (String w : written) { kind = branchKind(w); if (!kind.isEmpty()) break; }
        String found = find(g, n, seat, kind);
        if (found != null) {
            addAliases(store, g, found, written);
            return labelOf(g, found);
        }
        boolean branch = kind.equals("branch");
        boolean another = named(g, n).stream().anyMatch(id -> isBranch(g, id) == branch);
        String label = seat != null && !seat.isBlank() ? label(n, branch, seat, "") : another ? label(n, branch, "", firstMember == null ? "" : firstMember) : label(n, branch, "", "");
        Vocabulary nodes = Vocabulary.read(Graph.nodesFile(store));
        String base = label;
        for (int i = 2; taken(g, nodes, label) && i < 100; i++) label = base + " (" + i + ")";
        String id = Vocabulary.norm(label);
        nodes.put(new Vocabulary.Term(id, KIND + ": " + label, List.of(), ""));
        Graph.writeNodes(store, nodes);
        addAliases(store, g, id, written);
        return label;
    }

    private static boolean taken(Graph g, Vocabulary nodes, String label) {
        String id = Vocabulary.norm(label);
        return nodes.get(id) != null || nodes.resolve(label) != null || g.node(id) != null;
    }

    /** The written forms kept as a family's other names, each only where it leads to no other entry and does not name a family of its own. */
    private static void addAliases(LibraryStore store, Graph g, String id, List<String> written) throws IOException {
        if (written == null || written.isEmpty()) return;
        Vocabulary nodes = Vocabulary.read(Graph.nodesFile(store));
        Vocabulary.Term t = nodes.get(id);
        if (t == null) return;
        List<String> add = new ArrayList<>();
        for (String w : written) {
            String a = w == null ? "" : w.replaceAll("\\s*[,，、|]\\s*", " ").strip();
            if (a.isEmpty() || Vocabulary.norm(a).equals(id)) continue;
            String there = nodes.resolve(a);
            if (there != null && !there.equals(id)) continue;
            if (g.node(Vocabulary.norm(a)) != null && !g.nodeIdOf(a).equals(id)) continue;
            add.add(a);
        }
        if (add.isEmpty()) return;
        nodes.alias(id, t.description(), add);
        Graph.writeNodes(store, nodes);
    }

    // ── members ───────────────────────────────────────────────────────────────────────────────────────────────────────

    /** A membership claim's reading, for a claim a read or an answer files. Empty values are left out. */
    public static Map<String, String> detail(String how, String left, String from, String to, String role, String event, String eventOut) {
        Map<String, String> d = new LinkedHashMap<>();
        for (String[] kv : new String[][]{{"how", how}, {"left", left}, {"from", from}, {"to", to}, {"role", role}, {"event", event}, {"event-out", eventOut}})
            if (kv[1] != null && !kv[1].isBlank()) d.put(kv[0], kv[1].strip());
        return d;
    }

    /**
     * A membership as a sentence a person reads, without its date: "森田健二 entered 森田 family as 婿養子 (adopted and married)", "森田勇
     * belonged to 森田 family as its head".
     */
    public static String sentence(String person, String family, Map<String, String> detail) {
        Map<String, String> d = detail == null ? Map.of() : detail;
        String how = d.getOrDefault("how", ""), role = d.getOrDefault("role", "");
        String as = role.equals("head") ? " as its head" : role.equals("heir") ? " as its heir" : "";
        String said = switch (how) {
            case "birth" -> person + " was born into " + family + as;
            case "marriage" -> person + " married into " + family + as;
            case "adoption" -> person + " was adopted into " + family + as;
            case "mukoyoshi" -> person + " entered " + family + " as 婿養子 (adopted and married)" + (as.isEmpty() ? "" : "," + as);
            case "nyufu" -> person + " entered " + family + " by 入夫 marriage" + as;
            case "succession" -> person + " succeeded to " + family + as;
            case "founding" -> person + " founded " + family;
            default -> person + " belonged to " + family + as;
        };
        String left = d.getOrDefault("left", "");
        String out = switch (left) {
            case "" -> "";
            case "marriage-out" -> ", and left it on marrying out";
            case "adoption-out" -> ", and left it on being adopted into another family";
            case "branch" -> ", and left it to found a branch";
            case "death" -> ", until death";
            case "divorce" -> ", and left it on a divorce";
            case "adoption-ended" -> ", and left it when the adoption ended";
            default -> ", and left it";
        };
        return said + out;
    }

    /** The members, heads and branches of the families of one graph, read once. */
    private record Houses(Map<String, List<Membership>> byFamily, Map<String, List<Membership>> byPerson, Map<String, String> branchOf, Map<String, String> founder) { }

    private static final Map<Graph, Houses> MEMO = Collections.synchronizedMap(new WeakHashMap<>());

    /** The families of a graph read without the names over a life ({@link #houses}). */
    private static final Map<Graph, Houses> PLAIN = Collections.synchronizedMap(new WeakHashMap<>());

    /** The graphs whose families this thread is reading now. */
    private static final ThreadLocal<Set<Graph>> READING = ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));

    private static Houses houses(Graph g) {
        Houses h = MEMO.get(g);
        if (h != null) return h;
        // the order of a life's memberships follows the person's names; a name worked out from a membership, asked for while this reading
        // asks for the names, gets the memberships in the order their own years give, so neither reading waits on the other
        if (!READING.get().add(g)) {
            Houses plain = PLAIN.get(g);
            if (plain == null) { plain = read(g, false); PLAIN.put(g, plain); }
            return plain;
        }
        try { h = read(g, true); } finally { READING.get().remove(g); }
        MEMO.put(g, h);
        return h;
    }

    private static Houses read(Graph g, boolean byNames) {
        FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
        Map<String, List<Membership>> raw = new LinkedHashMap<>();   // person → memberships, as the claims give them
        Set<Membership> claimDated = Collections.newSetFromMap(new IdentityHashMap<>());   // those whose start is only the date of the claim
        Map<String, String> branchOf = new HashMap<>(), founder = new HashMap<>();
        for (Graph.Edge e : g.edges()) {
            if (FamilyKin.gone(e)) continue;
            switch (e.predicate()) {
                case MEMBER -> {
                    if (!isFamily(g, e.to())) continue;   // "Norway | member of | NATO" is nobody's family
                    Finding f = idx.finding(e.findingId());
                    Map<String, String> d = FamilyDetail.of(f);
                    FamilyDate from = date(idx, d.getOrDefault("from", ""), d.getOrDefault("event", ""));
                    FamilyDate to = date(idx, d.getOrDefault("to", ""), d.getOrDefault("event-out", ""));
                    String how = d.getOrDefault("how", ""), left = d.getOrDefault("left", "");
                    FamilyDate said = f == null ? null : FamilyChecks.claimDate(f);
                    boolean byClaim = false;
                    if (!left.isBlank() && !d.containsKey("from") && !d.containsKey("to")) {
                        // a claim that says how somebody left is dated by the leaving ("left the Morita family in 1940"), whatever it says of how
                        // they came in; its year is the coming in only for somebody born into the family in the year of their birth
                        FamilyDate born = idx.born(e.from());
                        if (how.equals("birth") && said != null && born != null && said.year() == born.year()) from = said;
                        else to = said;
                    } else if (from == null && !d.containsKey("from")) { from = said; byClaim = said != null; }
                    Membership m = new Membership(e.from(), e.to(), how, left, from, to, d.getOrDefault("role", ""),
                            d.getOrDefault("event", ""), d.getOrDefault("event-out", ""), List.of(e.findingId()), "", "", false);
                    if (byClaim) claimDated.add(m);
                    List<Membership> mine = raw.computeIfAbsent(e.from(), k -> new ArrayList<>());
                    int same = -1;
                    for (int i = 0; i < mine.size(); i++) if (oneMembership(mine.get(i), m)) { same = i; break; }
                    if (same < 0) mine.add(m);
                    else {
                        Membership was = mine.get(same), j = joined(was, m);
                        if (claimDated.contains(was.from() != null ? was : m)) claimDated.add(j);
                        mine.set(same, j);
                    }
                }
                case BRANCH -> { if (isFamily(g, e.from()) && isFamily(g, e.to())) branchOf.putIfAbsent(e.from(), e.to()); }
                case FOUNDED -> { if (isFamily(g, e.from())) founder.putIfAbsent(e.from(), e.to()); }
                default -> { }
            }
        }
        Map<String, List<String>> parents = new HashMap<>();
        FamilyKin.parents(g).forEach((child, links) -> { List<String> ps = new ArrayList<>(); for (FamilyKin.Link l : links) ps.add(l.other()); parents.put(child, ps); });
        Map<String, List<Membership>> byPerson = new LinkedHashMap<>(), byFamily = new LinkedHashMap<>();
        Map<Membership, Integer> cameIn = new IdentityHashMap<>();   // each membership → where it stands among a family's members
        for (Map.Entry<String, List<Membership>> p : raw.entrySet()) {
            String person = p.getKey();
            FamilyDate born = idx.born(person);
            FamilyNameHistory.Name first = byNames ? firstName(idx, person) : null;
            List<FamilyNameHistory.Name> later = new ArrayList<>();
            if (byNames) for (FamilyNameHistory.Name n : idx.names(person)) if (n != first && n.replaces() && n.from() != null) later.add(n);
            Map<Membership, Integer> inLife = new IdentityHashMap<>(), inFamily = new IdentityHashMap<>();
            Set<Membership> atBirth = Collections.newSetFromMap(new IdentityHashMap<>()), ofBirth = Collections.newSetFromMap(new IdentityHashMap<>());
            List<Membership> mine = new ArrayList<>();
            for (Membership m : p.getValue()) {
                boolean untold = m.how().isEmpty() || m.how().equals("unstated"), ofParent = untold && parentWasIn(raw, parents.getOrDefault(person, List.of()), m.family(), born);
                if (ofParent || untold && bears(idx, person, first, nameOf(labelOf(g, m.family())))) {
                    // the family of a birth parent then: a plain membership the claim gives no start of began at the birth, and the claim's own
                    // date is of something else ("left the Morita family in 1940"). A family of the first name's family part only may be another
                    // family of that name, so its claim keeps its year
                    if (ofParent && claimDated.contains(m) && (m.role().isEmpty() || m.role().equals("member")))
                        m = new Membership(m.person(), m.family(), m.how(), m.left(), null, m.to(), m.role(), m.event(), m.eventOut(), m.claims(), "", "", false);
                    ofBirth.add(m);
                }
                mine.add(m);
            }
            for (Membership m : mine) {
                String name = nameOf(labelOf(g, m.family()));
                // a membership whose start no claim gives: in the family of the first name, or of a birth parent then, it began at the birth
                if (m.how().equals("birth") || m.from() == null && ofBirth.contains(m)) atBirth.add(m);
                // or it began with the later name of that family
                Integer named = null;
                if (m.from() == null) for (FamilyNameHistory.Name n : later) if (bears(idx, person, n, name)) named = named == null ? n.from().year() : Math.min(named, n.from().year());
                int life, fam;
                if (m.from() != null) life = fam = m.from().year() * 2 + 1;
                else if (atBirth.contains(m)) { life = born != null ? born.year() * 2 : Integer.MIN_VALUE; fam = born != null ? life : Integer.MAX_VALUE; }
                else if (m.to() != null) life = fam = m.to().year() * 2;
                else if (named != null) life = fam = named * 2 + 1;
                else life = fam = Integer.MAX_VALUE;
                inLife.put(m, life);
                inFamily.put(m, fam);
            }
            List<Membership> ms = new ArrayList<>(mine);
            ms.sort(Comparator.comparingInt((Membership m) -> atBirth.contains(m) ? 0 : 1).thenComparingInt(inLife::get));
            List<Membership> done = new ArrayList<>();
            for (int i = 0; i < ms.size(); i++) {
                Membership m = ms.get(i);
                // the family before and after this one: a later role in the same family is no move
                String came = "", went = "";
                for (int k = i - 1; k >= 0 && came.isEmpty(); k--) if (!ms.get(k).family().equals(m.family())) came = ms.get(k).family();
                for (int k = i + 1; k < ms.size() && went.isEmpty(); k++) if (!ms.get(k).family().equals(m.family())) went = ms.get(k).family();
                boolean earlier = false;
                for (int k = 0; k < i; k++) earlier |= ms.get(k).family().equals(m.family());
                boolean worked = false;
                if (came.isEmpty() && !earlier && !atBirth.contains(m) && !m.how().equals("founding")) {
                    // nobody said where they came from: the family a birth parent belonged to when they were born
                    for (String parent : parents.getOrDefault(person, List.of())) {
                        for (Membership pm : raw.getOrDefault(parent, List.of())) {
                            if (pm.family().equals(m.family()) || (born != null && !pm.holds(born.year()))) continue;
                            came = pm.family(); worked = true; break;
                        }
                        if (!came.isEmpty()) break;
                    }
                }
                FamilyDate to = m.to();
                boolean toWorked = false;
                if (to == null && atBirth.contains(m) && !m.left().equals("death")) {
                    // the family they were born into: when no claim says they left it, and the next family took them in by an adoption, they
                    // left it where that membership began. A marriage says nothing of leaving the family one was born into
                    for (int k = i + 1; k < ms.size(); k++) {
                        Membership next = ms.get(k);
                        if (next.family().equals(m.family())) continue;
                        if (next.from() != null && (m.from() == null || next.from().year() >= m.from().year()) && ADOPTED_IN.contains(next.how())) { to = next.from(); toWorked = true; }
                        break;
                    }
                }
                Membership placed = new Membership(m.person(), m.family(), m.how(), m.left(), m.from(), to, m.role(), m.event(), m.eventOut(), m.claims(), came, went, worked, toWorked);
                cameIn.put(placed, inFamily.get(m));
                done.add(placed);
            }
            byPerson.put(person, done);
            for (Membership m : done) byFamily.computeIfAbsent(m.family(), k -> new ArrayList<>()).add(m);
        }
        for (List<Membership> ms : byFamily.values()) ms.sort(Comparator.comparingInt(cameIn::get));
        return new Houses(byFamily, byPerson, branchOf, founder);
    }

    /**
     * The name a person began life with, as far as the names say: the name at birth; else the name taken by the year of the birth; else,
     * when every other name the person carried has its year, the one that has none, which is the name those others replaced. Null when the
     * names do not say.
     */
    private static FamilyNameHistory.Name firstName(FamilyNameHistory.Index idx, String person) {
        FamilyNameHistory.Name b = idx.birth(person);
        if (b != null) return b;
        List<FamilyNameHistory.Name> replacing = idx.names(person).stream().filter(FamilyNameHistory.Name::replaces).toList();
        FamilyDate born = idx.born(person);
        if (born != null) for (FamilyNameHistory.Name n : replacing) if (n.from() != null && n.from().year() <= born.year()) return n;
        List<FamilyNameHistory.Name> undated = replacing.stream().filter(n -> n.from() == null).toList();
        return replacing.size() > 1 && undated.size() == 1 ? undated.get(0) : null;
    }

    /** Whether a name has this family name as its family part, in any of its forms. */
    private static boolean bears(FamilyNameHistory.Index idx, String person, FamilyNameHistory.Name n, String family) {
        if (n == null || family == null || family.isBlank()) return false;
        if (n.hasFamily(family)) return true;
        String part = n.family().isBlank() ? idx.parts(person, n.written())[0] : "";
        return !part.isBlank() && FamilyForms.sameForm(part, family);
    }

    /** Whether a birth parent was a member of the family when the child was born (a membership whose years are not given counts when the birth is not dated either). */
    private static boolean parentWasIn(Map<String, List<Membership>> raw, List<String> parents, String family, FamilyDate born) {
        for (String parent : parents) for (Membership pm : raw.getOrDefault(parent, List.of()))
            if (pm.family().equals(family) && (born != null ? pm.holds(born.year()) : pm.from() == null)) return true;
        return false;
    }

    private static FamilyDate date(FamilyNameHistory.Index idx, String written, String event) {
        if (written == null || written.isBlank()) return null;
        if (written.equalsIgnoreCase("event")) { Finding e = idx.finding(event); return e == null ? null : FamilyChecks.claimDate(e); }
        return FamilyDate.parse(written);
    }

    private static boolean overlap(Membership a, Membership b) {
        if (a.to() != null && b.from() != null && b.from().year() >= a.to().year()) return false;
        return b.to() == null || a.from() == null || a.from().year() < b.to().year();
    }

    private static boolean fits(String a, String b) { return a.isEmpty() || b.isEmpty() || a.equals(b); }

    /**
     * Whether two claims say one membership: the same family, years that meet and ways in that fit. Two different years of coming in are two
     * memberships, and so is a role (head, heir) one claim gives and the other does not, unless the other says nothing but the membership:
     * somebody born into a family in 1900 who became its head in 1950 was a member from 1900 and its head from 1950.
     */
    private static boolean oneMembership(Membership a, Membership b) {
        if (!a.family().equals(b.family()) || !overlap(a, b) || !fits(a.how(), b.how())) return false;
        if (a.from() != null && b.from() != null && a.from().year() != b.from().year()) return false;
        if (a.role().equals(b.role())) return true;
        if (!a.role().isEmpty() && !b.role().isEmpty()) return false;
        Membership plain = a.role().isEmpty() ? a : b;
        return plain.how().isEmpty() && plain.left().isEmpty() && plain.from() == null && plain.to() == null;
    }

    private static Membership joined(Membership a, Membership b) {
        List<String> claims = new ArrayList<>(a.claims()); for (String c : b.claims()) if (!claims.contains(c)) claims.add(c);
        return new Membership(a.person(), a.family(), a.how().isEmpty() ? b.how() : a.how(), a.left().isEmpty() ? b.left() : a.left(), a.from() != null ? a.from() : b.from(), a.to() != null ? a.to() : b.to(),
                a.role().isEmpty() ? b.role() : a.role(), a.event().isEmpty() ? b.event() : a.event(), a.eventOut().isEmpty() ? b.eventOut() : a.eventOut(), claims, "", "", false);
    }

    /** A family's members, by the year they came in (those whose year is not known last). */
    public static List<Membership> members(Graph g, String familyId) { return houses(g).byFamily().getOrDefault(familyId, List.of()); }

    /** A person's memberships, in the order of their lives: the family they were born into first, then by the year they came in. */
    public static List<Membership> families(Graph g, String personId) { return houses(g).byPerson().getOrDefault(personId, List.of()); }

    /** A family's heads in order: the members whose role was head, by the year they became it. */
    public static List<Membership> heads(Graph g, String familyId) {
        List<Membership> out = new ArrayList<>();
        for (Membership m : members(g, familyId)) if (m.role().equals("head")) out.add(m);
        return out;
    }

    /** The family this one is a branch of; null when no claim says. */
    public static String branchOf(Graph g, String familyId) { return houses(g).branchOf().get(familyId); }

    /** The families that are branches of this one. */
    public static List<String> branches(Graph g, String familyId) {
        List<String> out = new ArrayList<>();
        houses(g).branchOf().forEach((branch, of) -> { if (of.equals(familyId)) out.add(branch); });
        return out;
    }

    /** Who founded a family, by node id; null when no claim says. */
    public static String founder(Graph g, String familyId) { return houses(g).founder().get(familyId); }

    /** A family's hereditary head name, when a name claim on the family gives one; "" otherwise. */
    public static String hereditaryName(Graph g, String familyId) {
        for (FamilyNameHistory.Name n : FamilyNameHistory.of(g).names(familyId)) if (n.kind().equals("hereditary")) return n.written();
        return "";
    }

    /** The label of a family for a person to read, the same everywhere it is written. */
    public static String shown(Graph g, String familyId) { return familyId == null || familyId.isBlank() ? "" : labelOf(g, familyId); }
}
