package org.researchzosho.librarian;

import org.apache.commons.codec.language.DaitchMokotoffSoundex;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
/**
 * One person, written several ways. A tree site writes Hisa Endō, a book Endo Hisa, its index "Endo, Hisa", and a
 * register 森田 一郎 with a space that a web page leaves out. Read as they stand these are four people, and a child of theirs has four
 * fathers. A name's key is what stays the same across those ways of writing: the words of the name without accents, commas and order.
 * Two names with a key in common are one person. What tells two people of one name apart is never thrown away: a birth year or a
 * relation in brackets, Jr. and Sr., a middle name that only one of them has.
 */
public final class FamilyNames {

    private FamilyNames() { }

    /** The marks a reading puts where it could not read a character or a word: a box, the geta mark, "[unclear]", "[illegible]", "[?]". */
    private static final Pattern UNREAD = Pattern.compile("[□■◻▢〓]|\\[(?:unclear|illegible|unreadable|\\?)", Pattern.CASE_INSENSITIVE);

    /**
     * A name with a character nobody could read. It is kept as written, but it is not a key, a merge or a search: 髙橋□三郎 may be
     * 髙橋源三郎 or 髙橋平三郎, and only the record can tell.
     */
    public static boolean unreadable(String name) { return name != null && UNREAD.matcher(name).find(); }

    /** The keys of a name; empty when the name must only ever match itself (it carries a note that tells two people apart, is one word, or has a character nobody could read). */
    public static Set<String> keys(String name) {
        Set<String> out = new LinkedHashSet<>();
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || unreadable(n)) return out;
        if (n.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN || Character.UnicodeScript.of(cp) == Character.UnicodeScript.HIRAGANA || Character.UnicodeScript.of(cp) == Character.UnicodeScript.KATAKANA)) {
            if (n.matches(".*[(（].*")) return out;
            // an honorific or a rank is no part of the name: 遠藤健二さん and 子爵 遠藤健二 are 遠藤健二
            String joined = KanjiForms.modern(untitled(n)).replaceAll("[\\s　・]+", "");
            if (joined.codePointCount(0, joined.length()) >= 3) out.add(joined);
            return out;
        }
        // brackets: one capitalised word is another given name (Kenjirō (Hisa) Endō); anything else tells two people apart
        List<String> givenToo = new ArrayList<>();
        Matcher m = Pattern.compile("[(（]([^)）]*)[)）]").matcher(n);
        StringBuilder rest = new StringBuilder();
        int at = 0;
        while (m.find()) {
            String inside = m.group(1).strip();
            if (!inside.matches("\\p{Lu}[\\p{L}'’-]+")) return out;
            givenToo.add(inside);
            rest.append(n, at, m.start()).append(' '); at = m.end();
        }
        rest.append(n.substring(at));
        List<String> words = words(rest.toString());
        if (words.size() < 2) return out;
        // a title with one word (Mr. Endo, Viscount Endo) is a family name alone, which strangers share: it matches only itself
        if (words(untitled(rest.toString())).size() < 2) return out;
        out.add(sorted(words));
        // with the other given name in place of the first one given
        for (String g : givenToo) { List<String> alt = new ArrayList<>(words); String first = words(rest.toString().replace(",", " ")).isEmpty() ? "" : firstGiven(rest.toString()); if (!first.isEmpty() && alt.remove(first)) { alt.addAll(words(g)); out.add(sorted(alt)); } }
        return out;
    }

    /** In "Family, Given" the given name follows the comma; otherwise it is taken to be the first word. */
    private static String firstGiven(String name) {
        int comma = name.indexOf(',');
        List<String> w = words(comma >= 0 && !name.substring(comma + 1).strip().matches("(?i)(jr|sr|ii|iii|iv)\\.?") ? name.substring(comma + 1) : name);
        return w.isEmpty() ? "" : w.get(0);
    }

    private static List<String> words(String s) {
        String flat = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replaceAll("[,.\"“”]+", " ");
        List<String> out = new ArrayList<>();
        for (String w : flat.split("\\s+")) if (!w.isBlank()) out.add(w);
        return out;
    }

    private static String sorted(List<String> words) { String[] w = words.toArray(new String[0]); Arrays.sort(w); return String.join(" ", w); }

    /**
     * The people already in the library, by key. A key that two different people have is no use for telling who is meant, and is left out.
     */
    public static Map<String, String> known(Graph g) {
        Map<String, String> byKey = new LinkedHashMap<>();
        Set<String> shared = new LinkedHashSet<>();
        for (Graph.Node n : g.nodes()) {
            if (!n.kind().equals("person") || FamilyQuestions.placeholder(n.label())) continue;
            List<String> forms = new ArrayList<>(List.of(n.label())); forms.addAll(n.aliases());
            for (String f : forms) for (String k : keys(f)) { String had = byKey.putIfAbsent(k, n.label()); if (had != null && !had.equals(n.label())) shared.add(k); }
        }
        shared.forEach(byKey::remove);
        return byKey;
    }

    /** A name's sound, word by word, in the Daitch-Mokotoff codes genealogists use for European names: {family name codes, given name codes, given initial}. */
    public record Sound(Set<String> family, Set<String> given, String initial) { }

    private static final DaitchMokotoffSoundex DM = new DaitchMokotoffSoundex();

    /**
     * The sound of a name in Latin letters: the family name is the part before the comma of "Family, Given", else the last word; the
     * given name is the first word of the rest. Null for a name in another script, of one word, or with a note in brackets.
     */
    public static Sound sound(String name) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || unreadable(n) || n.matches(".*[(（].*") || n.codePoints().anyMatch(c -> Character.isLetter(c) && Character.UnicodeScript.of(c) != Character.UnicodeScript.LATIN)) return null;
        int comma = n.indexOf(',');
        List<String> family = comma >= 0 ? words(n.substring(0, comma)) : List.of(), given = words(comma >= 0 ? n.substring(comma + 1) : n);
        if (comma < 0) { if (given.size() < 2) return null; family = List.of(given.get(given.size() - 1)); given = given.subList(0, given.size() - 1); }
        given = given.stream().filter(w -> !w.matches("jr|sr|ii|iii|iv")).toList();
        if (family.isEmpty() || given.isEmpty()) return null;
        Set<String> f = codes(String.join("", family)), g = codes(given.get(0));
        return f.isEmpty() || g.isEmpty() ? null : new Sound(f, g, given.get(0).substring(0, 1));
    }

    /** The Daitch-Mokotoff codes of one word; a word gives more than one where a letter is read two ways (CH, CK, J). */
    public static Set<String> codes(String word) {
        Set<String> out = new LinkedHashSet<>();
        try { for (String c : DM.soundex(word == null ? "" : word).split("\\|")) if (!c.isBlank() && !c.equals("000000")) out.add(c); } catch (IllegalArgumentException ignored) { }
        return out;
    }

    /** Two names that sound alike: the family names share a code, and the given names share one or begin with the same letter. */
    public static boolean soundAlike(Sound a, Sound b) {
        if (a == null || b == null) return false;
        Set<String> f = new LinkedHashSet<>(a.family()); f.retainAll(b.family());
        Set<String> g = new LinkedHashSet<>(a.given()); g.retainAll(b.given());
        return !f.isEmpty() && (!g.isEmpty() || a.initial().equals(b.initial()));
    }

    /** The name the library already has this person under, or the name itself. */
    public static String resolve(Graph g, Map<String, String> known, String name) {
        if (g.node(g.nodeIdOf(name)) != null) return name;
        for (String k : keys(name)) { String label = known.get(k); if (label != null) return label; }
        return name;
    }

    /**
     * Another name that is safe to keep: not one word of a name in Latin letters (John, Endo), not a title with one word (Mr. Endo, Viscount
     * Endo, 遠藤さん), and not a part of the person's own name (源次 for 森田源次). A word like that is shared by strangers, and makes them
     * look like the same person.
     */
    public static boolean keepAsOtherName(String label, String other) {
        String o = other == null ? "" : other.strip();
        if (o.length() < 2 || o.equalsIgnoreCase(label)) return false;
        // how the teller is related to the person ("my father", "her uncle") is not a name
        if (o.toLowerCase(Locale.ROOT).matches("(my|his|her|our|their|the) .*") || FamilyQuestions.placeholder(o)) return false;
        // a title is no part of the name: what is kept or not is the name it stands before or after
        String u = untitled(o);
        boolean cjk = u.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
        if (cjk) { String l = KanjiForms.modern(label).replaceAll("\\s+", ""), x = KanjiForms.modern(u).replaceAll("\\s+", ""); return !(l.length() > x.length() && (l.startsWith(x) || l.endsWith(x))); }
        boolean latin = u.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN);
        return !latin || words(u).size() >= 2;
    }

    /**
     * Whether a name a claim writes is one word that a person's entry carries as another name: a family name or a given name alone, with a
     * title or without. In Latin letters it is one word (Hart, Mr. Hart, Tom); in characters, kana or hangul, which write a whole name
     * without a space, it is a part of a longer name of that person, its beginning or its end (遠藤 or 健二 of 遠藤健二). Strangers share a word like
     * that, so it leads a claim to the person only when it is the person's own name, the one the entry is filed under. An entry of anything
     * but a person, and a name the entry is filed under, are never one.
     */
    public static boolean oneWordOtherName(String written, Vocabulary.Term entry) {
        if (entry == null || !"person".equals(Graph.kindOf(entry.description()))) return false;
        String label = Graph.labelOf(entry.description());
        String w = untitled((written == null ? "" : written).replaceAll("\\s*[(（][^)）]*[)）]\\s*$", "").strip());
        if (w.isEmpty()) return false;
        String own = label.isEmpty() ? entry.slug() : label;
        if (Vocabulary.norm(comparable(w)).equals(Vocabulary.norm(comparable(own))) || Vocabulary.norm(w).equals(Vocabulary.norm(entry.slug()))) return false;
        // a script that writes a whole name without a space (characters, kana, hangul) has no words to count
        boolean unspaced = w.codePoints().anyMatch(cp -> cjk(cp) || Character.UnicodeScript.of(cp) == Character.UnicodeScript.HANGUL);
        if (!unspaced) return words(w).size() == 1;
        if (w.strip().split("[\\s　・]+").length > 1) return false;
        // there it is one word when it is a beginning or an end of a longer name of this person
        String x = KanjiForms.modern(w).replaceAll("[\\s　・]+", "");
        List<String> longer = new ArrayList<>(List.of(own));
        longer.addAll(entry.also());
        for (String l : longer) {
            String y = KanjiForms.modern(untitled(l)).replaceAll("[\\s　・]+", "");
            if (y.length() > x.length() && (y.startsWith(x) || y.endsWith(x))) return true;
        }
        return false;
    }

    /**
     * An other name as it is kept in the list of names: the index form "Endo, Kenji" as "Endo Kenji", the family part first as the index
     * wrote it, and no other comma. The list of names keeps other names apart by commas, so a name with a comma in it would come back as two
     * names, "Endo" and "Kenji", and a family name alone would then lead to this person from every source that writes it.
     */
    public static String asOtherName(String other) {
        String o = withoutIndexNote(other == null ? "" : other.strip());
        String[] ix = indexForm(o);
        return ix != null ? ix[0] + " " + ix[1] : o.replace(",", " ").replaceAll("\\s+", " ").strip();
    }

    // ── a title is no part of the name ───────────────────────────────────────────────────────────────────────────────

    /**
     * The words written before a name in Latin letters as a title, a rank or a form of address, by default: the everyday titles, the ranks of
     * the peerage, a priest's and a minister's, a doctor's and a professor's. The rule is that a title is not part of the name: "Mr. Endo",
     * "Viscount Endo", "Pastor Endo" and "Endo" write the one family name Endo, and "Mrs. Ruth Hale" the name Ruth Hale. These are the words
     * the rule knows, in the one place every name comparison and the link pass take them off; a title only counts where a name follows it.
     */
    static final Set<String> TITLES = Set.of("mr", "mrs", "miss", "ms", "dr", "doctor", "sir", "dame", "lady", "lord", "viscount", "viscountess", "baron", "baroness",
            "count", "countess", "marquis", "marquess", "marchioness", "prince", "princess", "father", "fr", "rev", "revd", "reverend", "pastor", "bishop",
            "brother", "sister", "mother", "professor", "prof");
    /** The same in Japanese, by default: the ranks written before a name (子爵 遠藤健二), and the honorifics written after it (遠藤さん, 遠藤様, 遠藤氏, 遠藤殿, 遠藤先生). */
    static final List<String> TITLES_BEFORE = List.of("公爵", "侯爵", "伯爵", "子爵", "男爵");
    static final List<String> HONORIFICS_AFTER = List.of("さん", "様", "氏", "殿", "先生", "翁", "ちゃん", "くん", "君");
    /** Japanese honorifics an English text writes after a name with a hyphen, by default: Endo-san, Endo-sensei, Haru-chan. */
    static final Set<String> HONORIFICS_LATIN = Set.of("san", "sama", "sensei", "chan", "shi", "dono", "senpai", "sempai");

    private static final Pattern TITLE_LATIN = Pattern.compile("^(\\p{L}+)\\.?\\s+(.*\\p{L}.*)$");
    private static final Pattern HONORIFIC_LATIN = Pattern.compile("(?i)^(.*\\p{L})-(\\p{L}+)$");

    /**
     * A name without its titles and honorifics ({@link #TITLES}): "Mr. Endo" is Endo, "Viscount Endo Kenji" Endo Kenji, "Pastor Endo" Endo,
     * "Endo-sensei" Endo, 遠藤さん 遠藤, 子爵 遠藤健二 遠藤健二. A title alone, with no name beside it, is returned as it is.
     */
    public static String untitled(String name) {
        String s = name == null ? "" : name.strip();
        for (int guard = 0; guard < 4; guard++) {
            Matcher m = TITLE_LATIN.matcher(s);
            if (m.matches() && TITLES.contains(m.group(1).toLowerCase(Locale.ROOT))) { s = m.group(2).strip(); continue; }
            Matcher hy = HONORIFIC_LATIN.matcher(s);
            if (hy.matches() && HONORIFICS_LATIN.contains(hy.group(2).toLowerCase(Locale.ROOT))) { s = hy.group(1).strip(); continue; }
            String before = null;
            for (String t : TITLES_BEFORE) if (s.startsWith(t) && s.length() > t.length()) { before = s.substring(t.length()).strip(); break; }
            if (before != null && !before.isEmpty()) { s = before; continue; }
            String after = null;
            for (String h : HONORIFICS_AFTER) {
                if (!s.endsWith(h) || s.length() <= h.length()) continue;
                String rest = s.substring(0, s.length() - h.length()).strip();
                if (!rest.isEmpty() && cjk(rest.codePointBefore(rest.length()))) { after = rest; break; }
            }
            if (after != null) { s = after; continue; }
            break;
        }
        return s;
    }

    /** Whether a name is written with a title or an honorific ({@link #untitled}). */
    public static boolean titled(String name) { return name != null && !untitled(name).equals(name.strip()); }

    /** Whether a name in Latin letters gives its given names only as initials: "K. Morita", "T. H. Hale", "Morita, K.". */
    public static boolean initials(String name) {
        String u = untitled(name == null ? "" : name.strip()).replaceAll("\\s*[(（][^)）]*[)）]\\s*$", "").strip();
        if (!FamilyForms.script(u).equals("latin")) return false;
        String[] ix = indexForm(u);
        if (ix != null) return ix[1].replaceAll("[\\s.]", "").length() <= 2;
        List<String> w = List.of(u.split("\\s+"));
        if (w.size() < 2) return false;
        for (int i = 0; i < w.size() - 1; i++) if (!w.get(i).matches("\\p{L}\\.?")) return false;
        return true;
    }

    /**
     * Whether a written form is how a text addresses a person, and no name of theirs: a title or an honorific with a family name alone
     * ("Mr. Hart", "Pastor Hart", "Hart-sensei", 遠藤さん), by the words alone. In characters, where a family name and a given name are
     * written without a space, only a name of two characters or fewer with an honorific counts here; the names of a person say more
     * ({@link FamilyNameHistory}). A title with a whole name ("Reverend Tom Hart") is that name, written with the title, and is not this.
     */
    public static boolean formOfAddress(String name) {
        String b = name == null ? "" : name.replaceAll("\\s*[(（][^)）]*[)）]\\s*$", "").strip();
        String u = untitled(b);
        if (u.equals(b) || u.isEmpty()) return false;
        if (FamilyForms.script(u).equals("latin")) return words(u).size() == 1;
        return !u.matches(".*[\\s　・].*") && u.codePointCount(0, u.length()) <= 2;
    }

    /**
     * Whether a name in Latin letters is one word, with a title or without: Endo, Mr. Endo, Viscount Endo, John. A name like that is shared by
     * strangers, so it finds nobody by itself.
     */
    public static boolean oneWord(String name) {
        String u = untitled(name == null ? "" : name.replaceAll("\\s*[(（][^)）]*[)）]\\s*$", ""));
        return FamilyForms.script(u).equals("latin") && words(u).size() == 1;
    }

    private static final Pattern SUFFIX = Pattern.compile("(?i)(?:jr|sr|ii|iii|iv|v)\\.?");

    /**
     * A name without the Jr., Sr. or numeral after it that tells a father and a son of one name apart: "Tom Hart, Jr." is Tom Hart. Within one
     * person's names it writes the same name; between two people it is what tells them apart, and {@link #keys} keeps it there.
     */
    public static String withoutSuffix(String name) {
        String s = name == null ? "" : name.strip();
        return s.replaceFirst("(?i)(?:,\\s*|\\s+)(?:jr|sr|ii|iii|iv|v)\\.?$", "").strip();
    }

    /**
     * The index form of a name in Latin letters, "Family, Given" as a book's index or a list writes it: {family, given}. Null for any other
     * name, and for "Tom Hart, Jr.", whose comma stands before a suffix ({@link #withoutSuffix}), not before a given name.
     */
    public static String[] indexForm(String name) {
        String n = withoutSuffix(name == null ? "" : name.replaceAll("\\s*[(（][^)）]*[)）]\\s*$", "").strip());
        int c = n.indexOf(',');
        if (c <= 0 || c != n.lastIndexOf(',')) return null;
        String family = n.substring(0, c).strip(), given = untitled(n.substring(c + 1).strip());
        if (family.isEmpty() || given.isEmpty() || SUFFIX.matcher(given).matches()) return null;
        if (!FamilyForms.script(family).equals("latin") || !FamilyForms.script(given).equals("latin")) return null;
        if (words(family).size() > 3 || words(given).size() > 3) return null;
        return new String[]{family, given};
    }

    // the words an index writes in brackets after a name for the person's relation to the book's subject: "(father)", "(wife)", "(brother; wife)"
    private static final Pattern RELATION_NOTE = Pattern.compile("(?i)^(?:(?:the |my |his |her |their )?(?:(?:author|narrator|writer)['’]s )?)"
            + "(?:(?:elder|eldest|older|oldest|younger|youngest|second|third|only|step|half|adoptive|adopted|foster|late|first)[- ]?\\s*)*"
            + "(?:(?:great[- ]?)*(?:grand[- ]?)?(?:father|mother|parent|son|daughter|child|brother|sister|sibling|husband|wife|spouse|uncle|aunt|nephew|niece|cousin)s?(?:[- ]in[- ]law)?"
            + "|父|母|両親|兄|弟|姉|妹|妻|夫|息子|娘|長男|次男|三男|長女|次女|三女|祖父|祖母|叔父|伯父|叔母|伯母|甥|姪|従兄弟|従姉妹|いとこ|孫)$");

    /**
     * The relation words an index writes in brackets after a name in its "Family, Given" form: "Morita, Kenji (father)" says the person is
     * the book's subject's father, and the name is "Morita, Kenji". {the name without the note, the note}; the note "" when there is none.
     * A bracket that holds a name too ("Morita, Haru (Helen; wife)") keeps the name: "Morita, Haru (Helen)". A name that is no index form
     * keeps its bracket as it is: "John Ellis (his son)" tells one John Ellis from another.
     */
    public static String[] indexNote(String name) {
        String n = name == null ? "" : name.strip();
        Matcher m = Pattern.compile("^(.*?)\\s*[(（]([^)）]*)[)）]\\s*$").matcher(n);
        if (!m.matches() || indexForm(m.group(1)) == null) return new String[]{n, ""};
        List<String> kept = new ArrayList<>(), notes = new ArrayList<>();
        for (String part : m.group(2).split("\\s*(?:[;；,、]|\\band\\b)\\s*")) {
            String p = part.strip();
            if (p.isEmpty()) continue;
            if (RELATION_NOTE.matcher(p).matches()) notes.add(p); else kept.add(p);
        }
        if (notes.isEmpty()) return new String[]{n, ""};
        return new String[]{kept.isEmpty() ? m.group(1).strip() : m.group(1).strip() + " (" + String.join("; ", kept) + ")", String.join("; ", notes)};
    }

    /** A name without the relation note an index writes after it ({@link #indexNote}). */
    public static String withoutIndexNote(String name) { return indexNote(name)[0]; }

    /**
     * A name without an index's relation note, whether or not its comma is still there: an other name kept from an index form loses its
     * comma ("Endō Kenji (brother)"), so a bracket that holds bare relation words alone is the index's note wherever it stands. A note with a
     * pronoun ("John Ellis (his son)") or a year is how a text tells two people of one name apart, and stays.
     */
    public static String withoutRelationNote(String name) {
        String n = withoutIndexNote(name);
        Matcher m = Pattern.compile("^(.*?\\p{L})\\s*[(（]([^)）]*)[)）]\\s*$").matcher(n);
        if (!m.matches()) return n;
        boolean any = false;
        for (String part : m.group(2).split("\\s*(?:[;；,、]|\\band\\b)\\s*")) {
            String p = part.strip();
            if (p.isEmpty()) continue;
            if (!RELATION_NOTE.matcher(p).matches() || p.matches("(?i)^(?:the|my|his|her|their)\\b.*")) return n;
            any = true;
        }
        return any ? m.group(1).strip() : n;
    }

    /**
     * A name as one person's names are compared, whatever way it is written: without its titles ({@link #untitled}), without Jr. or Sr. and
     * without the numeral of a hereditary name ({@link #withoutSuffix}, {@link #withoutGeneration}), and the index form "Endo, Kenji" as Endo
     * Kenji. "Mr. Endo" is Endo; "Viscount Endo, Kenji" is Endo Kenji. Between two people the marks that tell them apart count ({@link #keys}).
     */
    public static String comparable(String name) {
        String u = withoutGeneration(withoutSuffix(untitled(name == null ? "" : name)));
        String[] ix = indexForm(u);
        return ix != null ? ix[0] + " " + ix[1] : u;
    }

    /** The numeral written before a hereditary name in Japanese: 初代, 二代目, 三代, 先代, 当代. */
    private static final Pattern GENERATION = Pattern.compile("^(?:初代|先代|当代|[二三四五六七八九十]{1,3}代目?|[0-9０-９]{1,2}代目?)[\\s　]*(?=\\p{IsHan})");

    /**
     * A name without the numeral that says which holder of a hereditary name a person was: 初代 森田勇 is 森田勇 (as Tom Hart, Jr. is
     * Tom Hart). Within one person's names it writes the same name; between two people it is what tells them apart, and {@link #keys} keeps it.
     */
    public static String withoutGeneration(String name) {
        String s = name == null ? "" : name.strip();
        String t = GENERATION.matcher(s).replaceFirst("").strip();
        return t.isEmpty() ? s : t;
    }

    /**
     * The family names one person is written under, when there is more than one: "Mari Hale" and "Mari Morita" give Hale and Morita, 森田まり
     * and 髙橋まり give 森田 and 髙橋. Two forms count when they share the given name and differ in the family name. Empty otherwise.
     */
    public static List<String> surnames(List<String> forms) {
        Map<String, Set<String>> latin = new LinkedHashMap<>();   // given name → family names
        List<String> cjk = new ArrayList<>();
        for (String form : forms) {
            String f = form == null ? "" : form.replaceAll("\\s*[(（][^)）]*[)）]\\s*", " ").strip();
            if (f.isEmpty()) continue;
            if (f.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN || Character.UnicodeScript.of(c) == Character.UnicodeScript.HIRAGANA || Character.UnicodeScript.of(c) == Character.UnicodeScript.KATAKANA)) {
                String j = f.replaceAll("[\\s　・]+", "");
                if (cjk.stream().noneMatch(c -> KanjiForms.modern(c).equals(KanjiForms.modern(j)))) cjk.add(j);
                continue;
            }
            int comma = f.indexOf(',');
            String given, family;
            if (comma > 0) { family = f.substring(0, comma).strip(); given = f.substring(comma + 1).strip().split("\\s+")[0]; }
            else { String[] w = f.split("\\s+"); if (w.length < 2) continue; given = w[0]; family = w[w.length - 1]; }
            if (!given.isEmpty() && !family.isEmpty()) latin.computeIfAbsent(words(given).isEmpty() ? given : words(given).get(0), k -> new LinkedHashSet<>()).add(family);
        }
        Set<String> out = new LinkedHashSet<>();
        for (Set<String> families : latin.values()) {
            Set<String> distinct = new LinkedHashSet<>();
            for (String fam : families) if (distinct.stream().noneMatch(d -> words(d).equals(words(fam)))) distinct.add(fam);
            if (distinct.size() > 1) out.addAll(distinct);
        }
        for (int i = 0; i < cjk.size(); i++) for (int k = i + 1; k < cjk.size(); k++) {
            // compared in modern characters (髙 and 高 are one), written as the forms write them
            String a = KanjiForms.modern(cjk.get(i)), b = KanjiForms.modern(cjk.get(k));
            if (a.length() != cjk.get(i).length() || b.length() != cjk.get(k).length()) { a = cjk.get(i); b = cjk.get(k); }
            int same = 0;
            while (same < Math.min(a.length(), b.length()) - 1 && a.charAt(a.length() - 1 - same) == b.charAt(b.length() - 1 - same)) same++;
            String pa = cjk.get(i).substring(0, a.length() - same), pb = cjk.get(k).substring(0, b.length() - same);
            // a family name of one to three characters in front of the same given name
            if (same >= 1 && !KanjiForms.modern(pa).equals(KanjiForms.modern(pb)) && pa.length() <= 3 && pb.length() <= 3) { out.add(pa); out.add(pb); }
        }
        return new ArrayList<>(out);
    }

    /**
     * A name as it is compared when a person's name is looked up: the same with or without spaces between Chinese characters or kana (a
     * register writes 山田 太郎, a web page 山田太郎, and a full-width space is a space), in today's forms of the characters (髙 is 高), in
     * lower case. A space between two words in Latin letters stays: Taro Yamada is not Taroyamada.
     */
    public static String written(String name) {
        String n = KanjiForms.modern(Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFKC)).toLowerCase(Locale.ROOT).strip().replaceAll("\\s+", " ");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c == ' ' && i > 0 && i + 1 < n.length() && cjk(n.codePointBefore(i)) && cjk(n.codePointAt(i + 1))) continue;
            b.append(c);
        }
        return b.toString();
    }

    /** A Chinese character, a kana, or a mark written among them. */
    static boolean cjk(int cp) {
        Character.UnicodeScript s = Character.UnicodeScript.of(cp);
        return s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.HIRAGANA || s == Character.UnicodeScript.KATAKANA || (cp >= 0x3000 && cp <= 0x30ff);
    }

    /**
     * Two entries of the library's list of names that are one name by {@link #written}: {@code keep} the entry the facts rest on, {@code
     * empty} one no fact is about, each with the name a command reaches it by (its label, or its id where the label does not lead to it).
     */
    public record Twin(String keep, String keepName, int keepFacts, String empty, String emptyName) { }

    /**
     * The entries for a person that no fact is about and that are another person's name written with or without a space, or in another
     * form of its characters: a typed name found them and the person seemed not to exist (the owner's library, 2026-09-24). Such an entry
     * is one of the list of names that nothing leads to, or one the graph holds only because something mentions it when {@link
     * #sameByName} cannot pair it: a name of two characters, 李明 beside 李 明, which has no key. Each is paired with the entry of that
     * name the most facts are about. Two entries that facts are about are {@link #sameByName}'s.
     */
    public static List<Twin> twins(Graph g) { return twins(g, Set.of()); }

    /** The same, leaving out the pairs the family said are two people ({@link Graph#differentPairs}). */
    public static List<Twin> twins(Graph g, Set<String> apart) {
        Map<String, List<String[]>> byName = new LinkedHashMap<>();   // written name → [label, slug, node id or ""]
        for (Vocabulary.Term t : g.curated().terms().values()) {
            if (!"person".equals(Graph.kindOf(t.description()))) continue;
            String label = t.description().contains(":") ? t.description().substring(t.description().indexOf(':') + 1).strip() : t.slug();
            if (label.isBlank() || FamilyQuestions.placeholder(label) || unreadable(label)) continue;
            String id = g.nodeIdOf(t.slug());
            byName.computeIfAbsent(written(label), k -> new ArrayList<>()).add(new String[]{label, g.nodeIdOf(label).equals(id) ? label : t.slug(), g.node(id) == null ? "" : id});
        }
        for (Graph.Node n : g.nodes()) {
            if (!n.kind().equals("person") || FamilyQuestions.placeholder(n.label())) continue;
            List<String[]> same = byName.get(written(n.label()));
            if (same != null && same.stream().noneMatch(x -> x[2].equals(n.id()))) same.add(new String[]{n.label(), g.nodeIdOf(n.label()).equals(n.id()) ? n.label() : n.id(), n.id()});
        }
        List<Twin> out = new ArrayList<>();
        for (List<String[]> same : byName.values()) {
            String[] keep = null; int most = -1;
            for (String[] x : same) { int f = x[2].isEmpty() ? -1 : FamilyQuestions.facts(g, x[2]); if (f > most) { most = f; keep = x; } }
            if (keep == null || keep[2].isEmpty() || most <= 0) continue;   // no entry of this name has a fact: nothing leads anywhere yet
            for (String[] x : same) {
                if (x == keep || g.nodeIdOf(x[1]).equals(keep[2]) || g.nodeIdOf(x[0]).equals(keep[2]) || apart.contains(Graph.pair(g.nodeIdOf(x[1]), keep[2]))) continue;
                boolean empty = x[2].isEmpty() || (FamilyQuestions.facts(g, x[2]) == 0 && keys(x[0]).isEmpty());
                if (empty) out.add(new Twin(keep[0], keep[1], most, x[0], x[1]));
            }
        }
        return out;
    }

    /** People in the library who are one person by their names' keys: each pair is {the name that stays, the name folded into it}. The one with more facts stays. */
    public static List<String[]> sameByName(Graph g) { return sameByName(g, Set.of()); }

    /** The same, leaving out the pairs the family said are two people ({@link Graph#differentPairs}). */
    public static List<String[]> sameByName(Graph g, Set<String> apart) {
        Map<String, List<Graph.Node>> byKey = new LinkedHashMap<>();
        for (Graph.Node n : g.nodes()) {
            if (!n.kind().equals("person") || FamilyQuestions.placeholder(n.label())) continue;
            for (String k : keys(n.label())) byKey.computeIfAbsent(k, x -> new ArrayList<>()).add(n);
        }
        List<String[]> out = new ArrayList<>();
        Set<String> folded = new LinkedHashSet<>();
        for (List<Graph.Node> same : byKey.values()) {
            List<Graph.Node> distinct = same.stream().filter(n -> !folded.contains(n.id())).distinct().sorted((a, b) -> b.degree() - a.degree()).toList();
            for (int i = 1; i < distinct.size(); i++) if (!distinct.get(i).id().equals(distinct.get(0).id()) && !apart.contains(Graph.pair(distinct.get(0).id(), distinct.get(i).id()))) { out.add(new String[]{distinct.get(0).label(), distinct.get(i).label()}); folded.add(distinct.get(i).id()); }
        }
        return out;
    }
}
