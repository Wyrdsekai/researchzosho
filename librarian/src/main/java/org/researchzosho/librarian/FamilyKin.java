package org.researchzosho.librarian;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How two people are related, worked out from the birth-parent claims alone: up from each to the nearest ancestor they share, and
 * the number of generations on each side names the relation (a grandparent, an uncle, a second cousin once removed). Arithmetic
 * over the claims, no model. A relation the family wrote in words ("his grandson") can then be held against the one the tree gives.
 * The sex a claim records for a person lives here too, because the words for a relation depend on it.
 */
public final class FamilyKin {

    private FamilyKin() { }

    /** A birth parent or child: the other person's node and the claim that says so. */
    public record Link(String other, String finding) { }

    /** How {@code a} is related to {@code b}: generations up from each to {@code ancestor}, the claims on the way, and the words for it. */
    public record Relation(String a, String b, int upA, int upB, String ancestor, boolean half, List<String> findings, String words) { }

    /** The relation words a family writes, each with the generations it spans (the two sides, either way round); a cousin is any two of two or more. */
    private record Word(Pattern pattern, int near, int far) { }

    /** A claim that is kept apart: retired, replaced, or disputed, which the family said is wrong and which no longer counts. */
    static boolean gone(Graph.Edge e) { return e.disputed() || e.state().equals("retired") || e.state().equals("superseded"); }

    /** Each child's birth parents. Adoption, step and foster parents are not birth parents. */
    public static Map<String, List<Link>> parents(Graph g) {
        Map<String, List<Link>> out = new LinkedHashMap<>();
        for (Graph.Edge e : g.edges()) {
            if (gone(e)) continue;
            if (e.predicate().equals("parent-of")) out.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(new Link(e.from(), e.findingId()));
            else if (e.predicate().equals("child-of")) out.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(new Link(e.to(), e.findingId()));
        }
        return out;
    }

    // ── sex ──────────────────────────────────────────────────────────────────────────────────────────────────────────

    /** "male" or "female" for the ways a record writes it; "" for anything else. */
    public static String sexWord(String written) {
        String w = written == null ? "" : written.strip().toLowerCase(Locale.ROOT);
        if (w.matches("m|male|man|boy|男|男性|おとこ")) return "male";
        if (w.matches("f|female|woman|girl|女|女性|おんな")) return "female";
        return "";
    }

    /** What the claims record of each person's sex: node → sex → the claims. Two sexes for one person is a question for the checks. */
    public static Map<String, Map<String, List<String>>> sexes(Graph g) {
        Map<String, Map<String, List<String>>> out = new LinkedHashMap<>();
        for (Graph.Edge e : g.edges()) {
            if (gone(e) || !e.predicate().equals("sex")) continue;
            Graph.Node to = g.node(e.to());
            String s = sexWord(to == null ? "" : to.label());
            if (!s.isEmpty()) out.computeIfAbsent(e.from(), k -> new LinkedHashMap<>()).computeIfAbsent(s, k -> new ArrayList<>()).add(e.findingId());
        }
        return out;
    }

    /** One person's sex when the claims agree on it, else "". */
    public static String sexOf(Map<String, Map<String, List<String>>> sexes, String id) {
        Map<String, List<String>> s = sexes.get(id);
        return s == null || s.size() != 1 ? "" : s.keySet().iterator().next();
    }

    // the words by which a quote says which side of a parent-child claim is a man or a woman. A grandfather, a step-mother, a
    // father-in-law or a foster son is somebody else, and is left out
    private static final String NOT_LATIN = "(?<!step[- ])(?<!foster )(?<!adoptive )(?<!adopted )(?<!god)(?<!god[- ])(?<!great[- ])(?<!grand[- ])(?<!half[- ])";
    private static final Pattern FATHER = Pattern.compile("(?i)" + NOT_LATIN + "\\b(father|dad|papa)\\b(?![- ]in[- ]law)|(?<![祖曾曽高養義継叔伯大乳])(?:実父|父親|父)(?!母)");
    private static final Pattern MOTHER = Pattern.compile("(?i)" + NOT_LATIN + "\\b(mother|mum|mom|mama)\\b(?![- ]in[- ]law)|(?<![祖曾曽高養義継叔伯大乳父])(?:実母|母親|母)");
    private static final Pattern SON = Pattern.compile("(?i)" + NOT_LATIN + "\\b(son)\\b(?![- ]in[- ]law)|息子|長男|次男|二男|三男|四男|五男|末男");
    private static final Pattern DAUGHTER = Pattern.compile("(?i)" + NOT_LATIN + "\\b(daughter)\\b(?![- ]in[- ]law)|(?<![孫])娘(?!婿)|長女|次女|二女|三女|四女|末女");

    /**
     * The sex a parent-child claim's quote gives one of its two people: {@code [the person, "male"|"female"]}, or null. Only a quote
     * with words of one kind only says it: "her father" does, "his father and mother" does not say which.
     */
    public static String[] sexFromQuote(String relation, String subject, String object, String quote) {
        return sexFromQuote(relation, subject, object, quote, List.of(), List.of());
    }

    /**
     * The same, where {@code subjectForms} and {@code objectForms} are the other ways the two are written. A word counts for the person it
     * names, never for somebody else in the same words: a step of a chain ("my mother's uncle": the mother is not the uncle), a word right
     * beside the name of somebody who is not that person ("my mother (Morita Emi)" is Emi's word), and the word a party is written by
     * ("the writer of notes.txt's mother" is the one "my mother" speaks of) are not that person's.
     */
    public static String[] sexFromQuote(String relation, String subject, String object, String quote, Collection<String> subjectForms, Collection<String> objectForms) {
        if (quote == null || quote.isBlank() || !(relation.equals("parent-of") || relation.equals("child-of"))) return null;
        String q = Normalizer.normalize(quote, Normalizer.Form.NFKC);
        boolean down = relation.equals("parent-of");
        String parent = down ? subject : object, child = down ? object : subject;
        List<List<String>> two = parts(forms(parent, down ? subjectForms : objectForms), forms(child, down ? objectForms : subjectForms));
        String t = marked(q, two.get(0), two.get(1), List.of());
        List<String[]> said = new ArrayList<>();
        if (namesThem(FATHER, t, OWN, two.get(0), child)) said.add(new String[]{parent, "male"});
        if (namesThem(MOTHER, t, OWN, two.get(0), child)) said.add(new String[]{parent, "female"});
        if (namesThem(SON, t, OTHER, two.get(1), parent)) said.add(new String[]{child, "male"});
        if (namesThem(DAUGHTER, t, OTHER, two.get(1), parent)) said.add(new String[]{child, "female"});
        return said.size() == 1 ? said.get(0) : null;
    }

    /**
     * A person's ways of being written: the name itself, and the others given, without the bracket that tells namesakes apart; somebody written
     * only by a family name ("Tom Hale's parent (written only as Endo)") is written by that word.
     */
    private static List<String> forms(String name, Collection<String> others) {
        List<String> out = new ArrayList<>();
        for (String o : union(name, others)) {
            String[] mention = FamilyMentions.parts(o);
            String f = (mention != null ? mention[2] : o.replaceAll("\\s*[(（][^)）]*[)）]\\s*$", "")).strip();
            if (!f.isEmpty() && !out.contains(f)) out.add(f);
        }
        return out;
    }

    private static List<String> union(String name, Collection<String> others) {
        List<String> out = new ArrayList<>();
        if (name != null && !name.isBlank()) out.add(name);
        if (others != null) for (String o : others) if (o != null && !o.isBlank()) out.add(o);
        return out;
    }

    /**
     * Two people's ways of being written, with the parts a text writes them by alone: a given name in characters after the first two (正一 of
     * 髙橋正一), and each word of three letters or more of a name in Latin letters. A way both of them are written (a mother and her daughter of
     * one name, the family name they share) is nobody's in particular and is left out.
     */
    private static List<List<String>> parts(List<String> a, List<String> b) {
        Set<String> fullA = lower(a), fullB = lower(b), derA = lower(withParts(a)), derB = lower(withParts(b));
        // a full name both are written by (a mother and her daughter of one name) is nobody's in particular; a part of one that is the other's
        // whole name (Endo of Endo Kenji, beside Endo) is the other's
        List<String> ka = new ArrayList<>(), kb = new ArrayList<>();
        for (String x : a) if (!fullB.contains(x.toLowerCase(Locale.ROOT))) ka.add(x);
        for (String x : b) if (!fullA.contains(x.toLowerCase(Locale.ROOT))) kb.add(x);
        for (String x : withParts(a)) { String l = x.toLowerCase(Locale.ROOT); if (!fullB.contains(l) && !derB.contains(l) && !lower(ka).contains(l)) ka.add(x); }
        for (String x : withParts(b)) { String l = x.toLowerCase(Locale.ROOT); if (!fullA.contains(l) && !derA.contains(l) && !lower(kb).contains(l)) kb.add(x); }
        return List.of(ka, kb);
    }

    private static Set<String> lower(Collection<String> xs) { Set<String> out = new LinkedHashSet<>(); for (String x : xs) out.add(x.toLowerCase(Locale.ROOT)); return out; }

    private static List<String> withParts(List<String> names) {
        List<String> out = new ArrayList<>();
        for (String n : names) {
            boolean han = n.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
            String joined = n.replaceAll("[\\s　・]+", "");
            if (han && joined.codePointCount(0, joined.length()) >= 3) out.add(joined.substring(joined.offsetByCodePoints(0, 2)));   // a given part of one character (勇 of 森田勇) is marked only where no other character touches it
            if (!han) for (String w : n.split("[\\s,()（）]+")) if (w.length() >= 3 && !w.matches("(?i)the|and|mrs?|miss|sir|jr|sr")) out.add(w);
        }
        return out;
    }

    // the words that narrow a relation word: "his eldest daughter", "the late father"
    private static final String NARROW = "(?:(?:only|eldest|elder|oldest|older|younger|youngest|first|second|third|fourth|late|beloved|dear|own)\\s+)*";
    // a relation word that is a step of a chain: "my mother's uncle", "父の兄" (the mother, the father, is somebody else)
    private static final Pattern CHAIN_ON = Pattern.compile("(?i)^(?:s|ren)?(?:['’]s\\s+" + NARROW + "(?:great[- ]?)*(?:grand|step[- ]?)?(?:mother|father|parent|son|daughter|child|brother|sister|uncle|aunt|cousin|nephew|niece|wife|husband|spouse)"
            + "|\\s*の\\s*(?:父|母|兄|弟|姉|妹|息子|娘|長男|次男|長女|次女|叔父|伯父|叔母|伯母|祖父|祖母|夫|妻|子|甥|姪|孫))");
    // a name right after a relation word: a mark, a bracket's first word, words with capitals, or characters that are no particle
    private static final Pattern NAME_AFTER = Pattern.compile("^(?:s|ren)?\\s*[,:：、・]?\\s*(?:(?:named|called)\\s+)?[(（]?\\s*([\u0001\u0002]|\\p{Lu}[\\p{L}\\p{M}'’.-]*(?:\\s+\\p{Lu}[\\p{L}\\p{M}'’.-]*)*|(?![はがのをにともへでや])[\\p{IsHan}\\p{IsKatakana}\\p{IsHiragana}]{2,})");

    /**
     * Whether a word of the pattern stands in the marked words for the person marked {@code who}, written {@code whoForms}: a word right before
     * a name counts for that name only (a name of the same script as one of this person's that is none of them is somebody else's), a word that
     * is a step of a chain counts for nobody here, and the word the other party is written by is theirs ({@code other}, as written: "the writer
     * of notes.txt's mother").
     */
    private static boolean namesThem(Pattern word, String t, char who, List<String> whoForms, String other) {
        List<String> otherWords = FamilyAccount.relationWords(other);
        String theirs = otherWords.isEmpty() ? "" : otherWords.get(otherWords.size() - 1);
        for (Matcher m = word.matcher(t); m.find(); ) {
            String after = t.substring(m.end());
            if (CHAIN_ON.matcher(after).lookingAt()) continue;
            Matcher n = NAME_AFTER.matcher(after);
            if (n.lookingAt()) {
                String next = n.group(1);
                if (next.charAt(0) == who) return true;
                if (next.charAt(0) == OWN || next.charAt(0) == OTHER) continue;   // the other party's word
                String sc = script(next);
                if (whoForms.stream().anyMatch(f -> script(f).equals(sc))) continue;   // somebody else, named the way this person would be
            }
            if (!theirs.isEmpty() && relationWord(m.group()).equals(theirs)) continue;
            return true;
        }
        return false;
    }

    /** "latin" or "cjk", the script a name is written in. */
    private static String script(String name) {
        return name.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN) ? "latin" : "cjk";
    }

    /** A relation word as the chains of relation words write it: "Mother" and 母 are mother, 長男 is son. */
    private static String relationWord(String w) {
        String l = w.strip().toLowerCase(Locale.ROOT);
        if (l.matches("father|dad|papa|実父|父親|父")) return "father";
        if (l.matches("mother|mum|mom|mama|実母|母親|母")) return "mother";
        if (SON.matcher(l).matches()) return "son";
        if (DAUGHTER.matcher(l).matches()) return "daughter";
        return l;
    }

    // the words that say a person is a woman or a man, as defaults behind the rule "a word that says the person's sex stands in that
    // person's own sentences": pronouns, titles and kin words. A few compounds that name no person are left out (母校, 弟子, 工夫, 彼岸)
    private static final Pattern WOMAN = Pattern.compile("(?i)(?<![\\p{L}])(she|her|hers|herself|woman|wife|widow|mother|daughter|sister|grandmother|aunt|niece|mrs|miss|lady)(?![\\p{L}])"
            + "|彼女|妻|(?<!父)母(?![校国屋語])|娘(?!婿)|姉|妹|夫人|長女|次女|二女|三女|四女|五女|末女|養女|嫁|未亡人");
    private static final Pattern MAN = Pattern.compile("(?i)(?<![\\p{L}])(he|him|his|himself|man|husband|widower|father|son|brother|grandfather|uncle|nephew|mr|sir)(?![\\p{L}])"
            + "|彼(?![女岸方ら等])|(?<![丈工])夫(?![人婦])|父(?!母)|息子|兄|弟(?!子)|長男|次男|二男|三男|四男|五男|末男|婿");
    // the words of a marriage that are the husband's or the wife's, and of brothers and sisters: in a claim between two of them, the other one's
    private static final Pattern SPOUSE = Pattern.compile("(?i)(?<![\\p{L}])(husband|wife|widow|widower)s?(?![\\p{L}])|妻|(?<![丈工])夫(?![人婦])");
    private static final Pattern SIBLING = Pattern.compile("(?i)(?<![\\p{L}])(brother|sister)s?(?![\\p{L}])|兄|弟(?!子)|姉|妹");

    /**
     * The words of a claim's quote that may be the subject's own: a parent's without son and daughter, a child's without father and mother,
     * which are the other one's ("Isamu's son Shōji" says Shōji is a man, not Isamu); a husband's or a wife's without the words for either of
     * them (勇は妻ハルと結婚した: the wife is ハル), and a brother's or a sister's without the words for either. Any other claim's quote as it is.
     */
    public static String ownWords(String relation, String quote) {
        if (quote == null) return "";
        String q = Normalizer.normalize(quote, Normalizer.Form.NFKC);
        if ("parent-of".equals(relation)) return DAUGHTER.matcher(SON.matcher(q).replaceAll(" ")).replaceAll(" ");
        if ("child-of".equals(relation)) return MOTHER.matcher(FATHER.matcher(q).replaceAll(" ")).replaceAll(" ");
        if ("married-to".equals(relation)) return SPOUSE.matcher(q).replaceAll(" ");
        if ("sibling-of".equals(relation)) return SIBLING.matcher(q).replaceAll(" ");
        return q;
    }

    /**
     * The sex one sentence's own words say: "female" when words for a woman stand in it and none for a man, "male" the other way round, ""
     * when it has neither or both. A sentence that speaks of a woman and a man says nothing of which of them a person is.
     */
    public static String sexInWords(String sentence) {
        if (sentence == null || sentence.isBlank()) return "";
        String q = Normalizer.normalize(sentence, Normalizer.Form.NFKC);
        boolean woman = WOMAN.matcher(q).find(), man = MAN.matcher(q).find();
        return woman == man ? "" : woman ? "female" : "male";
    }

    // the marks a sentence's names become, so that a name's own letters are no word about anybody (the 夫 that ends a given name)
    private static final char OWN = '\u0001', OTHER = '\u0002';
    private static final Pattern PRONOUN = Pattern.compile("(?i)(?<![\\p{L}])(she|her|hers|herself|he|him|his|himself)(?![\\p{L}])|彼女|彼(?![女岸方ら等])");
    private static final Pattern OWN_NEXT = Pattern.compile("\\s*[,、]?\\s*[(（]?\\s*" + OWN), OTHER_NEXT = Pattern.compile("\\s*[,、]?\\s*[(（]?\\s*" + OTHER);
    private static final Pattern OWN_BEFORE = Pattern.compile(OWN + "\\s*(?:[)）]\\s*)?(?:['’]s|の)\\s*(?:\\p{L}+\\s+){0,2}$"), OF_OWN = Pattern.compile("\\s+of\\s+(?:\\p{L}+\\s+){0,2}" + OWN);
    private static final Pattern BESIDE_OTHER = Pattern.compile(OTHER + "(?:\\s*[(（][^)）]*[)）])?\\s*[,、]\\s*(?:\\p{L}+\\s+){0,2}$");

    /**
     * The sex one sentence's words say of one person, where the sentence may name other people too. {@code own}: the ways the sentence may name
     * the person; {@code others}: the ways it may name anybody else; {@code blank}: other name parts (family names, a name the sentence gives
     * as a name). A name's own letters say nothing. A word counts only where it is about this person: a pronoun where the sentence names
     * nobody before it and does not name this person after it ("She married Tom Ellis" is not Tom's), or where this person is the name
     * nearest before it; a kin word unless it belongs to another name, as this person's own relative ("Tom's sister Ann", "the daughter of
     * Isamu" for Isamu), right before another's name ("his wife Haru") or beside it ("John Hart, a widower"). A kin word that names this person
     * ("Hale's daughter Ruth") is theirs. Words for both, or for neither, say nothing.
     */
    public static String sexInWords(String sentence, Collection<String> own, Collection<String> others, Collection<String> blank) {
        if (sentence == null || sentence.isBlank()) return "";
        String t = marked(Normalizer.normalize(sentence, Normalizer.Form.NFKC), own, others, blank);
        Matcher first = PRONOUN.matcher(t);
        int ownAt = t.indexOf(OWN), firstPronoun = first.find() ? first.start() : Integer.MAX_VALUE;
        boolean woman = false, man = false;
        for (Pattern p : List.of(WOMAN, MAN)) for (Matcher m = p.matcher(t); m.find(); ) {
            boolean about;
            if (PRONOUN.matcher(m.group()).matches()) {
                about = ownAt >= 0 ? firstPronoun > ownAt && t.lastIndexOf(OWN, m.start()) > t.lastIndexOf(OTHER, m.start()) : t.lastIndexOf(OTHER, m.start()) < 0;
            } else {
                String before = t.substring(0, m.start()), after = t.substring(m.end());
                // a step of a chain ("his mother's brother") is somebody else: the brother is not the mother
                about = OWN_NEXT.matcher(after).lookingAt() || !(OWN_BEFORE.matcher(before).find() || OF_OWN.matcher(after).lookingAt()
                        || OTHER_NEXT.matcher(after).lookingAt() || BESIDE_OTHER.matcher(before).find() || CHAIN_ON.matcher(after).lookingAt());
            }
            if (!about) continue;
            if (p == WOMAN) woman = true; else man = true;
        }
        return woman == man ? "" : woman ? "female" : "male";
    }

    /** The sentence with each name the people carry as one mark: the person's own, another's, or a blank; the longest name first. */
    private static String marked(String q, Collection<String> own, Collection<String> others, Collection<String> blank) {
        char[] mark = new char[q.length()];
        List<Map.Entry<String, Character>> names = new ArrayList<>();
        for (String n : own) names.add(Map.entry(n, OWN));
        for (String n : others) names.add(Map.entry(n, OTHER));
        for (String n : blank) names.add(Map.entry(n, ' '));
        names.sort(Comparator.comparingInt(e -> -e.getKey().strip().length()));
        for (Map.Entry<String, Character> e : names) {
            String n = Normalizer.normalize(e.getKey().strip(), Normalizer.Form.NFKC);
            boolean latin = n.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN);
            if (n.isEmpty() || latin && n.length() < 2 || !q.contains(n)) continue;
            // a name of one character (勇) is the person's only where no other character touches it: not inside 勇太 or 髙橋勇
            boolean lone = n.codePointCount(0, n.length()) == 1 && Character.UnicodeScript.of(n.codePointAt(0)) == Character.UnicodeScript.HAN;
            String edge = lone ? "[\\p{IsLatin}\\p{M}\\p{IsHan}\\p{IsKatakana}]" : "[\\p{IsLatin}\\p{M}]";
            Matcher m = Pattern.compile("(?<!" + edge + ")" + Pattern.quote(n) + "(?!" + edge + ")").matcher(q);
            while (m.find()) {
                boolean free = true;
                for (int i = m.start(); i < m.end() && free; i++) free = mark[i] == 0;
                if (free) Arrays.fill(mark, m.start(), m.end(), e.getValue());
            }
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < q.length(); i++) {
            if (mark[i] == 0) b.append(q.charAt(i));
            else if (i == 0 || mark[i - 1] != mark[i]) b.append(mark[i]);
        }
        return b.toString();
    }

    // ── which of two people is the parent, by the words ──────────────────────────────────────────────────────────────

    // the words for a parent and for a child, one of them or several
    private static final Pattern PARENT_WORD = Pattern.compile("(?i)" + NOT_LATIN + "\\b(?:father|mother|parent|dad|mum|mom|papa|mama)(?:s)?\\b(?![- ]in[- ]law)"
            + "|(?<![祖曾曽高養義継叔伯大乳])(?:実父|実母|父親|母親|両親|父母|父|母)(?![方校国屋語])");
    private static final Pattern CHILD_WORD = Pattern.compile("(?i)" + NOT_LATIN + "\\b(?:son|daughter|child)(?:s|ren)?\\b(?![- ]in[- ]law)"
            + "|息子|(?<![孫])娘(?!婿)|長男|次男|二男|三男|四男|五男|末男|長女|次女|二女|三女|四女|五女|末女|子供|子ども|実子|(?<![\\p{IsHan}])[男女](?=\\s*[:：])");   // a register's own label "女：", "男："
    private static final Pattern PLURAL = Pattern.compile("(?i)parents|fathers|mothers|sons|daughters|children|両親|父母|子供|子ども");
    // the next name of a list after a word for several: ", and Tom Hale", "・健二", "と ハル"
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:,\\s*and|,|and|&|、|と|・)\\s*[(（]?\\s*([\u0001\u0002]|\\p{Lu}[\\p{L}\\p{M}'’.-]*(?:\\s+\\p{Lu}[\\p{L}\\p{M}'’.-]*)*|[\\p{IsHan}\\p{IsKatakana}]{2,})");
    private static final char A = OWN, B = OTHER;
    // the person the words make a relation word's owner, written before it: "Tom's daughter", "勇の長男", "my", "his"
    private static final Pattern OWNER_MARK = Pattern.compile("([\u0001\u0002])\\s*[)）]?\\s*(?:['’]s?|家?の)\\s*" + NARROW + "$");
    private static final Pattern OWNER_NAME = Pattern.compile("(?<![\\p{L}])\\p{Lu}[\\p{L}\\p{M}.-]*['’]s?\\s+" + NARROW + "$|[\\p{IsHan}\\p{IsKatakana}]+\\s*[)）]?\\s*の\\s*$");
    private static final Pattern OWNER_ME = Pattern.compile("(?i)(?<![\\p{L}])(?:my|our)\\s+" + NARROW + "$|(?:私|僕|わたし|俺)の\\s*$");
    private static final Pattern OWNER_THEM = Pattern.compile("(?i)(?<![\\p{L}])(?:his|her|their)\\s+" + NARROW + "$|彼女?の\\s*$");
    // written after it: "the daughter of Tom", "the son of a farmer"
    private static final Pattern OF_MARK = Pattern.compile("(?i)^(?:s|ren)?\\s+of\\s+(?:the\\s+late\\s+)?([\u0001\u0002])");
    private static final Pattern OF_NAME = Pattern.compile("^(?:s|ren)?\\s+of\\s+(?:the\\s+late\\s+)?\\p{Lu}");
    // the person a relation word names when it is written before it: "Ann, his daughter", "Ann was the daughter", "森田正一（長男）", "正一は勇の長男"
    private static final Pattern NAMED_BEFORE = Pattern.compile("(?i)([\u0001\u0002])\\s*[)）]?\\s*(?:[,、]\\s*(?:(?:who\\s+)?(?:was|is|became|being)\\s+)?|\\s+(?:who\\s+)?(?:was|is|became|being)\\s+|\\s*[(（]\\s*|\\s*[:：]\\s*)(?:(?:the|his|her|their|my|our|a|an|its)\\s+)?" + NARROW
            + "(?:[\u0001\u0002]\\s*(?:['’]s?|の)\\s*" + NARROW + ")?$|([\u0001\u0002])\\s*(?:は|が)\\s*(?:[\u0001\u0002]\\s*の\\s*)?$");

    /**
     * Which of two people the words make the parent: {@code "a"}, {@code "b"}, or "" when they do not say, or say both ways. A parent word
     * (father, mother, parents, 父, 母) or a child word (son, daughter, children, 長男, 娘) speaks of the person it names: the name right after it
     * ("his daughter Ann", "父：森田勇", "My parents, Ruth and Tom"), or before it ("Ann, his daughter", "Ann was the daughter", "森田正一（長男）").
     * Whose parent or child it names is the word's owner: "Tom's daughter Ann", "勇の長男正一" and "Ann, the daughter of Tom" say it of Tom, and
     * so of the two when Tom is the other; "my parents" say it of {@code teller}. A word whose owner is somebody else ("Kenji's father Tom", a
     * step of a chain such as "my mother's uncle") says nothing of these two. A word with no owner the words name (his, her, a register's
     * label "長男 正一") counts only when no word with a named owner says anything. A word that names neither of the two says nothing.
     */
    public static String parentByWords(String quote, Collection<String> aForms, Collection<String> bForms, String teller) {
        if (quote == null || quote.isBlank()) return "";
        List<List<String>> two = parts(forms(null, aForms), forms(null, bForms));
        String t = marked(Normalizer.normalize(quote, Normalizer.Form.NFKC), two.get(0), two.get(1), List.of());
        boolean tellerA = isTeller(aForms, teller), tellerB = isTeller(bForms, teller);
        boolean tellerUnknown = teller == null || teller.isBlank() || FamilyQuestions.placeholder(teller);
        int[] strong = new int[2], weak = new int[2];   // votes that a is the parent, that b is
        for (Pattern kind : List.of(PARENT_WORD, CHILD_WORD)) for (Matcher m = kind.matcher(t); m.find(); ) {
            String before = t.substring(0, m.start()), after = t.substring(m.end());
            if (CHAIN_ON.matcher(after).lookingAt()) continue;
            Set<Character> named = namedBy(m.group(), before, after);
            if (named == null) continue;   // the word speaks of somebody else, named right after it
            // the owner: a party, the teller, a pronoun or a label (unknown), or somebody else
            Character owner = null;
            boolean me = false, elsewhere = false;
            Matcher o = OWNER_MARK.matcher(before), of = OF_MARK.matcher(after);
            if (o.find()) owner = o.group(1).charAt(0);
            else if (of.lookingAt()) owner = of.group(1).charAt(0);
            else if (OWNER_ME.matcher(before).find()) me = true;
            else if (OWNER_THEM.matcher(before).find()) { }   // his, her, their: an owner the words do not name
            else if (OWNER_NAME.matcher(before).find() || OF_NAME.matcher(after).lookingAt()) elsewhere = true;
            if (elsewhere) continue;
            for (char x : named) {
                if (owner != null && owner == x) continue;
                char y = x == A ? B : A;
                boolean parent = kind == PARENT_WORD;
                int who = (parent ? x : y) == A ? 0 : 1;   // the one this word makes the parent
                boolean firm = owner != null && owner == y || me && (y == A ? tellerA : tellerB);
                if (firm) strong[who]++;
                else if (!me || tellerUnknown) weak[who]++;
            }
        }
        int[] v = strong[0] + strong[1] > 0 ? strong : weak;
        return v[0] > 0 && v[1] == 0 ? "a" : v[1] > 0 && v[0] == 0 ? "b" : "";
    }

    /**
     * The people a relation word names, by their marks: the name right after it, the names of a list after a word for several, or the name
     * before it. Null when the name after it is somebody else's: the word speaks of that person.
     */
    private static Set<Character> namedBy(String word, String before, String after) {
        Set<Character> out = new LinkedHashSet<>();
        Matcher n = NAME_AFTER.matcher(after);
        if (n.lookingAt()) {
            char c = n.group(1).charAt(0);
            // a name the marks cut: "Morita \u0002" is one of the two, written with a word the forms did not give
            Matcher into = Pattern.compile("^\\s+([\u0001\u0002])").matcher(after.substring(n.end()));
            if (c != A && c != B && into.lookingAt()) c = into.group(1).charAt(0);
            if (c == A || c == B) out.add(c);
            else if (!PLURAL.matcher(word).matches()) return null;
            // a word for several names each of a list: "My parents, Ruth Hale and Tom Hale"; a name that is neither of the two is somebody else
            if (PLURAL.matcher(word).matches()) {
                String rest = after.substring(n.end());
                for (Matcher more = LIST_ITEM.matcher(rest); more.lookingAt(); more = LIST_ITEM.matcher(rest)) {
                    char d = more.group(1).charAt(0);
                    if (d == A || d == B) out.add(d);
                    rest = rest.substring(more.end());
                }
            }
            return out;
        }
        Matcher b = NAMED_BEFORE.matcher(before);
        if (b.find()) out.add((b.group(1) != null ? b.group(1) : b.group(2)).charAt(0));
        return out;
    }

    /** Whether one of a person's ways of being written is the teller, the one the words' "I" is. */
    private static boolean isTeller(Collection<String> forms, String teller) {
        if (teller == null || teller.isBlank() || forms == null) return false;
        for (String f : forms) if (FamilyAccount.isWriter(f, teller)) return true;
        return false;
    }

    // ── what kind of relation the words say ──────────────────────────────────────────────────────────────────────────

    /**
     * What the words say of a parent or a child, by kind: how many words of each kind stand in them, each as a whole word with its modifiers.
     * {@code others}: plain words that name or belong to somebody who is neither of the two people ("Nora Lindqvist, Tom's daughter", 子爵 森田勇
     * 二男); {@code asIf}: words of a likeness ("like a father", "a father figure"), which say no parent.
     */
    public record Kinds(int plain, int grand, int step, int inLaw, int god, int adoptive, int foster, int chainOwned, int others, int asIf) {
        public Kinds(int plain, int grand, int step, int inLaw, int god, int adoptive, int foster, int chainOwned) { this(plain, grand, step, inLaw, god, adoptive, foster, chainOwned, 0, 0); }

        /**
         * The kind the words say instead of a birth parent, when they say no plain parent word: the one other kind, or "several"; "as-if" when
         * they say only a likeness, "others" when their parent words are somebody else's; "" otherwise.
         */
        public String instead() {
            if (plain > 0) return "";
            List<String> kinds = new ArrayList<>();
            if (grand > 0) kinds.add("grand");
            if (step > 0) kinds.add("step");
            if (inLaw > 0) kinds.add("in-law");
            if (god > 0) kinds.add("god");
            if (adoptive > 0) kinds.add("adoptive");
            if (foster > 0) kinds.add("foster");
            if (kinds.size() == 1) return kinds.get(0);
            if (kinds.size() > 1) return "several";
            return asIf > 0 ? "as-if" : others > 0 ? "others" : chainOwned > 0 ? "chain" : "";
        }
    }

    // a parent word that is a step to somebody who is no relative: "my mother's friend", "his father's employer", 父の友人
    private static final Pattern FRIEND_ON = Pattern.compile("(?i)^(?:s|ren)?['’]s?\\s+(?:(?:old|close|good|best|dear|childhood|school|family|business|former)\\s+)*(?:friends?|hosts?|hostess|teachers?|employers?|neighbou?rs?|colleagues?|guests?|landlord|landlady|maid|servants?|nurse|doctor|pastor|priest|patron|boss|partners?|shop|house|home|firm|company|village|town|side|family|line)(?![\\p{L}])"
            + "|^\\s*の\\s*(?:友人|知人|恩師|先生|同僚|主人|上司|家|店|会社|側|実家)");
    // words of a likeness around a parent word: "like a father to me", "a second mother", "a father figure"
    private static final Pattern AS_IF_BEFORE = Pattern.compile("(?i)(?<![\\p{L}])(?:like|as|almost|practically|virtually)\\s+(?:a|an|my|his|her|their|our)?\\s*(?:second|real|true|surrogate|substitute|foster)?\\s*$|(?:a|an)\\s+(?:second|surrogate|substitute)\\s+$|(?:のよう|みたい)な\\s*$");
    // "took him as his son and heir", "adopted as a son": a taking, not a likeness
    private static final Pattern TAKEN_AS = Pattern.compile("(?i)(?<![\\p{L}])(?:took|takes|taken|taking|adopted|adopts|adopting|received|receives|raised|reared|brought\\s+up)\\s+(?:[\\p{L}\\p{M}'’.\\-\u0001\u0002]+\\s+){0,4}?as\\s+(?:a|an|my|his|her|their|our)?\\s*$");
    private static final Pattern AS_IF_AFTER = Pattern.compile("(?i)^\\s*(?:figure|to\\s+(?:me|us|him|her|them|all))(?![\\p{L}])|^\\s*(?:同然|代わり|のよう|のような|みたい|がわり)");
    // a title before a name in Japanese, which is no name: 子爵 森田勇 二男
    private static final Pattern J_TITLE = Pattern.compile("(?:公爵|侯爵|伯爵|子爵|男爵|夫人|氏|様|殿|さん|先生|博士|大将|中将|少将|大佐|中佐|少佐|大尉|中尉|少尉|議員|知事|市長|社長|会長|頭取|教授|牧師|司祭|住職)$");
    // somebody else named as the owner of a relation word: a name in Latin letters with 's, or a name in characters right before it (森田勇の二男, 森田勇 二男)
    private static final Pattern THIRD_OWNER = Pattern.compile("(?:(?<![\\p{L}])\\p{Lu}[\\p{L}\\p{M}.'’-]*(?:\\s+\\p{Lu}[\\p{L}\\p{M}.'’-]*)+['’]s?\\s+" + NARROW + "|(?<![\\p{IsHan}\\p{IsKatakana}])[\\p{IsHan}\\p{IsKatakana}]{3,5}\\s*(?:の)?\\s*)$");
    // somebody else named right before a relation word as the one it calls: "Nora Lindqvist, Tom's daughter", "Ann Hale, the daughter of"
    private static final Pattern THIRD_NAMED_BEFORE = Pattern.compile("(?<![\\p{L}])\\p{Lu}[\\p{L}\\p{M}.'’-]*(?:\\s+\\p{Lu}[\\p{L}\\p{M}.'’-]*)+\\s*,\\s*(?:(?:the|his|her|their|my|our|a|an)\\s+)?" + NARROW
            + "(?:(?:\\p{Lu}[\\p{L}\\p{M}.'’-]*(?:\\s+\\p{Lu}[\\p{L}\\p{M}.'’-]*)*|[\u0001\u0002])\\s*(?:['’]s?|の)\\s*" + NARROW + ")?$");

    /**
     * Whether a plain parent word is somebody else's, for the two people written {@code a} and {@code b} (marked in the words): its owner is a
     * third person named before it, or the one it names right after or right before it is a third person. A word with no owner and no one
     * named, or whose owner or named one is one of the two, is the pair's.
     */
    private static final Pattern PRONOUN_OWNER = Pattern.compile("(?i)(?<![\\p{L}])(?:his|her|their)\\s+" + NARROW + "$|彼女?の\\s*$");
    private static final Pattern A_LATIN_NAME = Pattern.compile("(?<![\\p{L}])\\p{Lu}[\\p{L}\\p{M}.'’-]+(?:\\s+\\p{Lu}[\\p{L}\\p{M}.'’-]+)*"), A_CJK_NAME = Pattern.compile("[\\p{IsHan}\\p{IsKatakana}]{2,}");

    /** Whose a his, her or their is: the nearest name before it, "party" for one of the two (a mark), "third" for anybody else, "none" for nobody. */
    private static String pronounOwner(String before, Set<String> scripts) {
        // a name in the possessive ("Ken Ellis's daughter … her father") is no antecedent: the pronoun is the head's, the daughter's
        int party = -1, third = -1;
        for (Matcher m = Pattern.compile("[\u0001\u0002](?!\\s*(?:['’]s|の))").matcher(before); m.find(); ) party = m.start();
        for (Pattern p : List.of(A_LATIN_NAME, A_CJK_NAME)) for (Matcher m = p.matcher(before); m.find(); ) {
            if (!scripts.contains(FamilyForms.script(m.group())) || J_TITLE.matcher(m.group()).find() || m.group().matches("(?i)the|a|an|in|at|on|for|when|after|before|my|his|her|their|our|she|he|they|we|it|but|and|or|so|then|now|later|also|both")) continue;
            if (before.substring(m.end()).matches("(?s)\\s*(?:['’]s|の).*")) continue;
            third = Math.max(third, m.start());
        }
        return party < 0 && third < 0 ? "none" : party >= third ? "party" : "third";
    }

    private static boolean somebodyElses(String t, Matcher m, String word, boolean known, List<Set<String>> scripts) {
        if (!known) return false;
        String before = t.substring(0, m.start()), after = t.substring(m.end());
        Set<String> union = new LinkedHashSet<>(scripts.get(0)); union.addAll(scripts.get(1));
        // the owner: one of the two (a mark), somebody else (a name), or a his, her or their whose nearest name says whose
        Character owner = null;
        Matcher om = OWNER_MARK.matcher(before), direct = Pattern.compile("([\u0001\u0002])\\s*[)）]?\\s*$").matcher(before);
        if (om.find()) owner = om.group(1).charAt(0); else if (direct.find()) owner = direct.group(1).charAt(0);
        boolean ownerParty = owner != null;
        // the one a party's word names is the other party, in the other's scripts; a word nobody of the two owns may name either
        Set<String> expect = owner == null ? union : owner == OWN ? scripts.get(1) : scripts.get(0);
        Matcher third = THIRD_OWNER.matcher(before);
        // a house (遠藤家の次男) is no person: whose word it is, the mention rules weigh
        boolean ownerThird = !ownerParty && third.find() && !third.group().strip().matches("[\\p{IsHan}\\p{IsKatakana}]+家\\s*の?") && union.contains(FamilyForms.script(third.group())) && !J_TITLE.matcher(before.strip()).find();
        Matcher pr = PRONOUN_OWNER.matcher(before);
        if (!ownerParty && !ownerThird && pr.find()) {
            String whose = pronounOwner(before.substring(0, pr.start()), union);
            if (whose.equals("third")) ownerThird = true; else if (whose.equals("party")) ownerParty = true;
        }
        // the one named: right after ("daughter Ann", 長男 正一) or right before ("Ann, his daughter")
        Matcher n = NAME_AFTER.matcher(after);
        boolean namedParty = false, namedThird = false;
        if (n.lookingAt()) {
            char c = n.group(1).charAt(0);
            Matcher into = Pattern.compile("^\\s+([\u0001\u0002])").matcher(after.substring(n.end()));
            if (c != OWN && c != OTHER && into.lookingAt()) c = into.group(1).charAt(0);
            // a record's word for not knowing (父 不詳, "father unknown") names nobody
            boolean unknown = FamilyAccount.UNKNOWN_WORDS.matcher(n.group(1)).find();
            // a third person only by a full name: a given name or a family name alone is somebody the mention rules weigh
            boolean full = n.group(1).matches(".*\\s.*") || n.group(1).codePoints().anyMatch(x -> Character.UnicodeScript.of(x) == Character.UnicodeScript.HAN || Character.UnicodeScript.of(x) == Character.UnicodeScript.KATAKANA) && n.group(1).codePointCount(0, n.group(1).length()) >= 3;
            if (c == OWN || c == OTHER) namedParty = true; else if (!PLURAL.matcher(word).matches() && !unknown && full && expect.contains(FamilyForms.script(n.group(1)))) namedThird = true;
        } else if (NAMED_BEFORE.matcher(before).find()) namedParty = true;
        else if (THIRD_NAMED_BEFORE.matcher(before).find() && expect.contains("latin")) namedThird = true;
        return ownerThird || namedThird && !namedParty;
    }

    private static final String PC = "(?:father|mother|parent|son|daughter|child|children|dad|mum|mom|papa|mama|pa|ma)s?";
    private static final Pattern GRAND_WORD = Pattern.compile("(?i)(?<![\\p{L}])(?:great[- ]?)*grand[- ]?" + PC + "(?![\\p{L}])|[曾曽高]?祖父母?|[曾曽高]祖母|(?<!孫)祖母|[曾曽玄]?孫(?:息子|娘)?|ひ孫");
    private static final Pattern STEP_WORD = Pattern.compile("(?i)(?<![\\p{L}])step[- ]?" + PC + "(?![\\p{L}])|継父|継母|継親|継子|まま(?:父|母)");
    private static final Pattern IN_LAW_WORD = Pattern.compile("(?i)(?<![\\p{L}])" + PC + "[- ]in[- ]law(?![\\p{L}])|義父|義母|義理の(?:父|母|息子|娘|両親)|(?<![\\p{IsHan}])舅|(?<![\\p{IsHan}])姑");
    private static final Pattern GOD_WORD = Pattern.compile("(?i)(?<![\\p{L}])god[- ]?" + PC + "(?![\\p{L}])|代父|代母");
    private static final Pattern ADOPTIVE_WORD = Pattern.compile("(?i)(?<![\\p{L}])adopt(?:ive|ed)\\s+" + PC + "(?![\\p{L}])|養父|養母|養子|養女|養親");
    private static final Pattern FOSTER_WORD = Pattern.compile("(?i)(?<![\\p{L}])foster\\s+" + PC + "(?![\\p{L}])|里親|里子");

    /**
     * The kinds of parent and child words the quote carries. A plain word is a father, a mother, a son, a daughter, a child or a parent
     * standing on its own: not a step of a chain ("my mother's grandfather": the mother is a step), not a grandparent, a step-parent, an
     * in-law, a godparent, an adoptive or a foster parent, which are counted as their own kinds.
     */
    public static Kinds kinds(String quote) { return kinds(quote, List.of(), List.of()); }

    /**
     * The same, for the two people the words are about, written {@code aWays} and {@code bWays}: a plain word that names or belongs to somebody
     * else ("Nora Lindqvist, Tom's daughter" for Tom and Ann; 子爵 森田勇 二男 for two others) is counted as somebody else's, not as the pair's, and a
     * word of a likeness ("like a father to me") or a step to no relative ("my mother's friend") is no parent word.
     */
    public static Kinds kinds(String quote, Collection<String> aWays, Collection<String> bWays) {
        String q = Normalizer.normalize(quote == null ? "" : quote, Normalizer.Form.NFKC);
        List<List<String>> two = parts(forms(null, aWays), forms(null, bWays));
        // nothing is known of whose a word is when a party has no way of being written left (two namesakes), or when none was given
        boolean known = !two.get(0).isEmpty() && !two.get(1).isEmpty();
        // the scripts each of the two is written in: a name in another script (Morita Tadashi beside 森田正) may be that person, and is nobody else
        List<Set<String>> scripts = new ArrayList<>();
        for (List<String> ways : two) { Set<String> s = new LinkedHashSet<>(); for (String w : ways) s.add(FamilyForms.script(w)); scripts.add(s); }
        String t = known ? marked(q, two.get(0), two.get(1), List.of()) : q;
        int plain = 0, chain = 0, others = 0, asIf = 0;
        for (Pattern p : List.of(PARENT_WORD, CHILD_WORD)) for (Matcher m = p.matcher(t); m.find(); ) {
            String after = t.substring(m.end()), before = t.substring(0, m.start());
            if (CHAIN_ON.matcher(after).lookingAt() || FRIEND_ON.matcher(after).lookingAt()) { chain++; continue; }
            if (AS_IF_BEFORE.matcher(before).find() && !TAKEN_AS.matcher(before).find() || AS_IF_AFTER.matcher(after).lookingAt()) { asIf++; continue; }
            if (somebodyElses(t, m, m.group(), known, scripts)) { others++; continue; }
            plain++;
        }
        return new Kinds(plain, count(GRAND_WORD, q), count(STEP_WORD, q), count(IN_LAW_WORD, q), count(GOD_WORD, q), count(ADOPTIVE_WORD, q), count(FOSTER_WORD, q), chain, others, asIf);
    }

    private static final Pattern SPOUSE_LABEL = Pattern.compile("(?i)(?<![\\p{L}])(?:wife|wives|husband|husbands|spouses?|consort)(?![\\p{L}])|妻|(?<![丈工])夫(?![人婦])|配偶者|後妻|先妻|前妻|継室|正室|側室");

    /** Whether the words say a wife or a husband, by a spouse word, and no brother, sister, parent or child word: two names beside "妻：" are two wives, not brother and sister. */
    public static boolean spousesOnly(String quote) { return spousesOnly(quote, List.of(), List.of()); }

    /** The same, for the two people written {@code aWays} and {@code bWays}: a parent or child word that is somebody else's ("男爵 遠藤勇 三女" of a wife) says nothing of the two. */
    public static boolean spousesOnly(String quote, Collection<String> aWays, Collection<String> bWays) {
        String q = Normalizer.normalize(quote == null ? "" : quote, Normalizer.Form.NFKC);
        return SPOUSE_LABEL.matcher(q).find() && siblingWords(q) == 0 && kinds(q, aWays, bWays).plain() == 0;
    }

    private static int count(Pattern p, String q) { int n = 0; for (Matcher m = p.matcher(q); m.find(); ) n++; return n; }

    private static final Pattern SIBLING_WORD = Pattern.compile("(?i)(?<![\\p{L}])(?<!step[- ])(?<!half[- ])(?:brother|sister|sibling)s?(?![\\p{L}])(?![- ]in[- ]law)|(?<![義従])(?:兄|弟|姉|妹|兄弟|姉妹)(?![子嫁婿])");
    private static final String A_RELATION = "(?:great[- ]?)*(?:grand)?(?:mother|father|parent|son|daughter|husband|wife|uncle|aunt|brother|sister)";
    private static final String J_RELATION = "(?:祖父|祖母|父|母|夫|妻|叔父|伯父|叔母|伯母|兄|弟|姉|妹)";
    // a sibling word owned by a relative the words leave to be found: "her father's younger brother", "Tom Hale's mother's sister", "mother's
    // father's brother", 母の父の弟. "My mother's brother" is the account's own relative, whom the account may name, and is not this
    private static final Pattern OWNED_BY_RELATIVE = Pattern.compile("(?i)(?:(?:his|her|their)\\s+" + A_RELATION + "['’]s\\s+" + NARROW
            + "|(?<![\\p{L}])\\p{Lu}[\\p{L}\\p{M}'’.-]*['’]s\\s+" + A_RELATION + "['’]s\\s+" + NARROW
            + "|" + A_RELATION + "['’]s\\s+" + A_RELATION + "['’]s\\s+" + NARROW
            + "|" + J_RELATION + "の" + J_RELATION + "の)$");

    /**
     * How the quote's sibling words stand: 1 when at least one stands on its own ("his brother Tom", 弟の健二) or is owned by the account's
     * own relative ("my mother's brother"), -1 when every one of them is owned by a relative the words leave to be found ("her father's
     * younger brother", "Tom Hale's mother's sister", 母の父の弟: a brother of that relative, whoever the model named), 0 when the quote has none.
     */
    public static int siblingWords(String quote) {
        String q = Normalizer.normalize(quote == null ? "" : quote, Normalizer.Form.NFKC);
        int own = 0, owned = 0;
        for (Matcher m = SIBLING_WORD.matcher(q); m.find(); ) {
            if (OWNED_BY_RELATIVE.matcher(q.substring(0, m.start())).find()) owned++; else own++;
        }
        return own > 0 ? 1 : owned > 0 ? -1 : 0;
    }

    // ── the relation between two people ──────────────────────────────────────────────────────────────────────────────

    /** Every ancestor of a person with the generations up and the claims on the way, nearest first. */
    static Map<String, List<String>> ancestors(Map<String, List<Link>> parents, String id) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        out.put(id, List.of());
        ArrayDeque<String> todo = new ArrayDeque<>(List.of(id));
        while (!todo.isEmpty()) {
            String at = todo.poll();
            for (Link p : parents.getOrDefault(at, List.of())) {
                if (out.containsKey(p.other())) continue;
                List<String> path = new ArrayList<>(out.get(at)); path.add(p.finding());
                out.put(p.other(), path);
                todo.add(p.other());
            }
        }
        return out;
    }

    /** How {@code a} is related to {@code b} by birth, or null when the claims hold no line between them. */
    public static Relation of(Graph g, Map<String, List<Link>> parents, Map<String, Map<String, List<String>>> sexes, String a, String b) {
        if (a.equals(b)) return null;
        Map<String, List<String>> upA = ancestors(parents, a), upB = ancestors(parents, b);
        String best = null; int bestSum = Integer.MAX_VALUE;
        for (String x : upA.keySet()) if (upB.containsKey(x) && upA.get(x).size() + upB.get(x).size() < bestSum) { best = x; bestSum = upA.get(x).size() + upB.get(x).size(); }
        if (best == null) return null;
        int ua = upA.get(best).size(), ub = upB.get(best).size();
        boolean half = false;
        if (ua == 1 && ub == 1) {
            Set<String> pa = new LinkedHashSet<>(), pb = new LinkedHashSet<>();
            for (Link l : parents.getOrDefault(a, List.of())) pa.add(l.other());
            for (Link l : parents.getOrDefault(b, List.of())) pb.add(l.other());
            Set<String> both = new LinkedHashSet<>(pa); both.retainAll(pb);
            half = pa.size() == 2 && pb.size() == 2 && both.size() == 1;
        }
        List<String> ids = new ArrayList<>(upA.get(best)); ids.addAll(upB.get(best));
        return new Relation(a, b, ua, ub, best, half, ids, words(ua, ub, half, sexOf(sexes, a)));
    }

    /** What {@code a} is to {@code b}, for {@code upA} and {@code upB} generations up to the ancestor they share: "grandfather", "first cousin once removed". */
    public static String words(int upA, int upB, boolean half, String sexA) {
        boolean m = sexA.equals("male"), f = sexA.equals("female");
        String greats = "great-".repeat(Math.max(0, Math.max(upA, upB) - 2));
        if (upA == 0) return upB == 1 ? (m ? "father" : f ? "mother" : "parent") : greats + (m ? "grandfather" : f ? "grandmother" : "grandparent");
        if (upB == 0) return upA == 1 ? (m ? "son" : f ? "daughter" : "child") : greats + (m ? "grandson" : f ? "granddaughter" : "grandchild");
        if (upA == 1 && upB == 1) return (half ? "half-" : "") + (m ? "brother" : f ? "sister" : "brother or sister");
        if (upA == 1) return "great-".repeat(upB - 2) + (m ? "uncle" : f ? "aunt" : "uncle or aunt");
        if (upB == 1) return "great-".repeat(upA - 2) + (m ? "nephew" : f ? "niece" : "nephew or niece");
        int degree = Math.min(upA, upB) - 1, removed = Math.abs(upA - upB);
        String[] ordinal = {"", "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth"};
        String[] times = {"", " once removed", " twice removed", " three times removed", " four times removed"};
        return (degree < ordinal.length ? ordinal[degree] : degree + "th") + " cousin" + (removed == 0 ? "" : removed < times.length ? times[removed] : " " + removed + " times removed");
    }

    /**
     * The sentence for two people: by birth, or through one marriage ("Ann Hart was married to Tom Hale, who is Kimie Hale's father").
     * Each person is named by their heading ({@link #named}), so a woman who took her husband's name reads "Mary Ellis (born Hale)". Null when
     * the claims connect them in neither way.
     */
    public static String said(Graph g, String a, String b) {
        Map<String, List<Link>> parents = parents(g);
        Map<String, Map<String, List<String>>> sexes = sexes(g);
        String an = named(g, a), bn = named(g, b);
        for (Graph.Edge e : g.edges()) if (!gone(e) && e.predicate().equals("married-to") && (e.from().equals(a) && e.to().equals(b) || e.from().equals(b) && e.to().equals(a)))
            return an + " was married to " + bn + " (" + e.findingId() + ").";
        Relation r = of(g, parents, sexes, a, b);
        if (r != null) return an + " is " + bn + "'s " + r.words() + (r.upA() > 0 && r.upB() > 0 ? ": their nearest shared ancestor is " + named(g, r.ancestor()) : "") + ". The claims on the way: " + String.join(", ", r.findings().stream().distinct().toList()) + ".";
        for (Graph.Edge e : g.edges()) {
            if (gone(e) || !e.predicate().equals("married-to")) continue;
            for (String[] side : new String[][]{{e.from(), e.to()}, {e.to(), e.from()}}) {
                if (side[0].equals(a) && !side[1].equals(b)) { Relation s = of(g, parents, sexes, side[1], b); if (s != null) return an + " was married to " + named(g, side[1]) + " (" + e.findingId() + "), who is " + bn + "'s " + s.words() + ". The claims on the way: " + String.join(", ", s.findings().stream().distinct().toList()) + "."; }
                if (side[0].equals(b) && !side[1].equals(a)) { Relation s = of(g, parents, sexes, a, side[1]); if (s != null) return an + " is the " + s.words() + " of " + named(g, side[1]) + ", who was married to " + bn + " (" + e.findingId() + "). The claims on the way: " + String.join(", ", s.findings().stream().distinct().toList()) + "."; }
            }
        }
        return null;
    }

    /**
     * A person as a comparison of two entries names them ({@link FamilySame}, which the checks quote): by the entry's name as the library files
     * it, the name its commands take, with the bracket that tells namesakes apart ("Ann Hale (born 1941)"). The heading only where it begins
     * with that name and adds the birth name beside it ("森田健二 (born 遠藤)"); a heading worked out from a later name ("Ann Hart" for the
     * entry Ann Hale (born 1941)) would name the entry by words its claims are not written under and lose the bracket.
     */
    static String label(Graph g, String id) {
        Graph.Node n = g.node(id);
        if (n == null) return id;
        String heading = FamilyNameHistory.of(g).heading(id);
        return heading.equals(n.label()) || heading.startsWith(n.label() + " (") ? heading : n.label();
    }

    /**
     * A person as a sentence names them: their heading, the latest name with the birth name beside it ("森田健二 (born 遠藤)"), keeping the
     * bracket of the entry's name that tells namesakes apart when the heading does not carry it ({@link FamilyNamePages#withBracket}).
     */
    static String named(Graph g, String id) { Graph.Node n = g.node(id); return n == null ? id : FamilyNamePages.withBracket(FamilyNameHistory.of(g).heading(id), n.label()); }

    // ── a relation the family wrote in words, against the tree ─────────────────────────────────────────────────────

    // longest first: a great-grandson is not also a grandson, 曾祖父 is not also 祖父
    private static final List<Word> WORDS = List.of(
            new Word(Pattern.compile("(?i)\\bgreat[- ]great[- ]grand(?:child|son|daughter|parent|father|mother|children)\\b|玄孫|高祖父|高祖母"), 0, 4),
            new Word(Pattern.compile("(?i)\\bgreat[- ]grand(?:child|son|daughter|parent|father|mother|children)\\b|曾孫|曽孫|ひ孫|曾祖父|曾祖母|曽祖父|曽祖母"), 0, 3),
            new Word(Pattern.compile("(?i)\\b(?:great|grand)[- ](?:uncle|aunt|nephew|niece)\\b|大叔父|大伯父|大叔母|大伯母|又甥|又姪"), 1, 3),
            new Word(Pattern.compile("(?i)\\bgrand(?:child|son|daughter|parent|father|mother|children)\\b|孫|祖父|祖母"), 0, 2),
            new Word(Pattern.compile("(?i)\\b(?:uncle|aunt|nephew|niece)\\b|叔父|伯父|叔母|伯母|甥|姪"), 1, 2),
            new Word(Pattern.compile("(?i)\\bcousin\\b|いとこ|従兄弟|従姉妹|従兄|従弟|従姉|従妹"), 2, -1));

    /** The generations a relation word spans, {near, far} (far -1: a cousin, any two of two or more); null when the words say several relations or none. */
    static int[] spanOf(String quote) {
        if (quote == null) return null;
        String q = Normalizer.normalize(quote, Normalizer.Form.NFKC);
        if (Pattern.compile("(?i)\\bin[- ]law\\b|\\bstep|義理|義父|義母|義兄|義弟|義姉|義妹|継父|継母|継子").matcher(q).find()) return null;
        int[] found = null;
        for (Word w : WORDS) {
            Matcher m = w.pattern().matcher(q);
            if (!m.find()) continue;
            if (found != null && (found[0] != w.near() || found[1] != w.far())) return null;
            found = new int[]{w.near(), w.far()};
            q = m.replaceAll(" ");
        }
        return found;
    }

    /** Whether a relation of {@code upA} and {@code upB} generations is the one the words say. */
    static boolean fits(int[] span, int upA, int upB) {
        int near = Math.min(upA, upB), far = Math.max(upA, upB);
        return span[1] < 0 ? near >= 2 : near == span[0] && far == span[1];
    }
}
