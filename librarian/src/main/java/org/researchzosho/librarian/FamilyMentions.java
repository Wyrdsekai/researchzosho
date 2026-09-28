package org.researchzosho.librarian;

import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A person written only by a family name ("Endo's son, Morita Kenji"). The words stay as the source wrote them; the party is filed as a
 * described person, {@code 森田健二's father (written only as Endo)}, linked to the family by a membership, and who that is becomes a link
 * of its own: a merge that says why it was made and can be taken back ({@code researchzosho graph unmerge}), made by the library only when
 * one person bore that family name at that date, fits the relation in the phrase and has a second fact that agrees; otherwise the family is
 * asked. A family name alone is never a person of its own and never dropped as noise.
 *
 * <p>The candidates and the linking are the library's rule, deterministic; the model only marks which party the source writes by a family
 * name alone, and the code checks that: the words around the party make it somebody known by a family name or a family, and the word is
 * the family part of a name the library or the same read knows. A given name alone is never a family name.
 */
public final class FamilyMentions {

    private FamilyMentions() { }

    /** Who links a described person to a person by the library's own rule, as merges.tsv writes it. */
    public static final String BY = "genealogy names";

    /** A described person made for a party written only by a family name: {@code <person>'s <role> (written only as <word>)}. */
    static final Pattern MENTION = Pattern.compile("^(.+?)['’]s (.+?) \\(written only as (.+)\\)$");

    /** The described person for a party the source writes only by a family name: "森田健二's father (written only as Endo)". */
    public static String placeholder(String person, String role, String word) {
        return (person == null ? "" : person.strip()) + "'s " + (role == null || role.isBlank() ? "relative" : role.strip()) + " (written only as " + (word == null ? "" : word.strip()) + ")";
    }

    /** Whether a name is such a described person ({@link #placeholder}). */
    public static boolean isMention(String name) { return name != null && MENTION.matcher(name.strip()).matches(); }

    /** {person, role, word} of a described person ({@link #placeholder}); null for any other name. */
    public static String[] parts(String name) {
        Matcher m = name == null ? null : MENTION.matcher(name.strip());
        return m != null && m.matches() ? new String[]{m.group(1).strip(), m.group(2).strip(), m.group(3).strip()} : null;
    }

    // ── step 1: a family, or a person known by a family name ─────────────────────────────────────────────────────────

    /** The detail key with which the reader marks a fact whose party the model says the source writes by a family name alone. */
    static final String MARKED = "only-family-name";
    /** The detail key for the nearest earlier full name of the same passage whose family part is the word: a candidate, never an answer. */
    static final String NEAR = "near";

    /** Kin words a possessive or an "of" puts next to a family name, by default; the rule is the phrase, these are where it looks first. */
    private static final String KIN = "sons?|daughters?|child|children|father|mother|parents?|wife|husband|widow(?:er)?|heirs?|brothers?|sisters?|grand(?:son|daughter|child|father|mother)|nephew|niece|cousin|uncle|aunt"
            + "|son-in-law|daughter-in-law|father-in-law|mother-in-law|step(?:son|daughter)|boy|girl";
    private static final String KIN_JA = "息子|娘|長男|次男|二男|三男|四男|五男|長女|次女|二女|三女|四女|末子|養子|養女|跡継ぎ|跡取り|後継ぎ|未亡人|子|父|母|妻|夫|嫁|婿|兄|弟|姉|妹|孫|甥|姪";
    private static final String ORDINAL = "(?:(?:eldest|elder|oldest|younger|youngest|first|second|third|fourth|only|adopted|late|own)\\s+)?";
    /** The words for a family that may stand between a name in characters and its の: 遠藤家の次男 is the second son of the 遠藤 house. */
    private static final String HOUSE_JA = "(?:家|一族|一門|本家|分家|宗家)?";

    private static String norm(String s) { return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKC); }

    /** A family name alone as the words of a phrase put it: one word in Latin letters, or characters with no space; null for anything else. */
    static String word(String party) {
        String n = party == null ? "" : norm(party).replaceAll("\\s*[(（][^)）]*[)）]\\s*$", "").strip();
        if (n.isEmpty() || isMention(n) || FamilyQuestions.placeholder(n)) return null;
        if (n.matches("\\p{IsLatin}[\\p{IsLatin}'’.-]*")) return n;
        String sc = FamilyForms.script(n);
        if ((sc.equals("han") || sc.equals("kana")) && !n.matches(".*[\\s　・].*")) return n;
        return null;
    }

    /**
     * Whether the phrase makes the word somebody known by a family name: "Endo's son", "son of Endo", 遠藤の子, and a kin label beside the word
     * alone as a table or a register writes it ("Father: Hale", 父 遠藤, "Hale (father)").
     */
    static boolean possessive(String word, String quote) {
        if (word == null || word.isBlank() || quote == null) return false;
        String q = norm(quote), w = Pattern.quote(norm(word));
        if (FamilyForms.script(word).equals("latin"))
            return Pattern.compile("(?i)(?<!\\p{L})" + w + "['’]s?\\s+" + ORDINAL + "(?:" + KIN + ")\\b").matcher(q).find()
                    || Pattern.compile("(?i)\\b(?:" + KIN + ")\\s+of\\s+(?:the\\s+)?(?:late\\s+)?" + w + "(?!\\p{L})(?-i:(?!\\s+\\p{Lu}))").matcher(q).find()
                    || Pattern.compile("(?i)\\b(?:" + KIN + ")\\s*[:：|]\\s*" + w + "(?!\\p{L})(?-i:(?!\\s+\\p{Lu}))").matcher(q).find()
                    || Pattern.compile("(?i)(?<!\\p{L})" + w + "\\s*[(（]\\s*(?:" + KIN + ")\\s*[)）]").matcher(q).find();
        return Pattern.compile(w + HOUSE_JA + "(?:氏|さん|様|殿)?\\s*[の之]\\s*(?:" + KIN_JA + ")").matcher(q).find()
                || Pattern.compile("(?:^|[\\s|｜（(・:：、])(?:" + KIN_JA + ")\\s*[:：|｜]?\\s*" + w + "(?![\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}])").matcher(q).find()
                || Pattern.compile(w + "\\s*[(（]\\s*(?:" + KIN_JA + ")\\s*[)）]").matcher(q).find();
    }

    /**
     * Whether the kin word of the relation stands on the family itself, not on a person written by the family name: "the second son of the
     * Endo family", "the Endo family's daughter", 遠藤家の次男. Somebody of that family is then the other party, and who, the family is asked.
     */
    static boolean kinOfTheFamily(String word, String quote, String relation) { return kinOfTheFamilyWords(word, quote, relation) != null; }

    /** The words that put the kin word on the family ("the adopted son of the Endo family", 遠藤家の養子); null when none do. */
    private static String kinOfTheFamilyWords(String word, String quote, String relation) {
        String kind = kinKind(relation);
        if (word == null || word.isBlank() || quote == null || kind == null) return null;
        String q = norm(quote), w = Pattern.quote(norm(word));
        String[] k = KIN_OF.get(kind);
        List<Pattern> ways = FamilyForms.script(word).equals("latin")
                ? List.of(Pattern.compile("(?i)\\b" + ORDINAL + "(?:" + k[0] + ")\\s+of\\s+(?:the\\s+)?(?<!\\p{L})" + w + "\\s+(?:family|house|line|clan|dynasty)\\b"),
                        Pattern.compile("(?i)(?<!\\p{L})" + w + "\\s+(?:family|house|line|clan|dynasty)['’]s?\\s+" + ORDINAL + "(?:" + k[0] + ")\\b"))
                : List.of(Pattern.compile(w + "(?:家|一族|一門|本家|分家|宗家)(?:氏|さん|様|殿)?\\s*[の之]\\s*(?:" + k[1] + ")"));
        for (Pattern p : ways) { Matcher m = p.matcher(q); if (m.find()) return m.group(); }
        return null;
    }

    /** Words that make a child's entry into the family other than by birth, in the kin words on the family: "the adopted son of the Endo family", 遠藤家の養子. */
    private static final Pattern NOT_BY_BIRTH = Pattern.compile("(?i)\\badopted\\b|\\bstep|養子|養女|婿|嫁");

    /**
     * Whether the word stands in the quote on its own, outside the words that speak of it as a family and outside a longer name: "Endo, the last
     * head of the Endo family, died", 遠藤家の当主であった遠藤は. The words then write somebody by the family name; where the word stands only
     * inside the family's own words ("born into the Endo family", 遠藤家に生まれた), they write the family.
     */
    static boolean standsAlone(String word, String quote) {
        if (word == null || word.isBlank() || quote == null) return false;
        String q = norm(quote), w = norm(word);
        String phrase;
        while ((phrase = familyPhrase(w, q)) != null && q.contains(phrase)) q = q.replace(phrase, " | ");
        if (FamilyForms.script(w).equals("latin")) {
            Matcher m = Pattern.compile("\\p{IsLatin}[\\p{IsLatin}\\p{M}’'.-]*").matcher(q);
            List<int[]> spans = new ArrayList<>();
            while (m.find()) spans.add(new int[]{m.start(), m.end()});
            for (int i = 0; i < spans.size(); i++) {
                String t = q.substring(spans.get(i)[0], spans.get(i)[1]).replaceAll("['’]s?$", "").replaceAll("[.-]+$", "");
                if (t.isEmpty() || !FamilyForms.sameForm(t, w)) continue;
                // a capitalised word right before or after it, with nothing but a space between, makes it part of a longer name (Endō Shōichi)
                boolean before = i > 0 && q.substring(spans.get(i - 1)[1], spans.get(i)[0]).matches("[ \\t]+") && Character.isUpperCase(q.codePointAt(spans.get(i - 1)[0]));
                boolean after = i + 1 < spans.size() && q.substring(spans.get(i)[1], spans.get(i + 1)[0]).matches("[ \\t]+") && Character.isUpperCase(q.codePointAt(spans.get(i + 1)[0]))
                        && !q.substring(spans.get(i)[0], spans.get(i)[1]).matches(".*['’]s?");
                if (!before && !after) return true;
            }
            return false;
        }
        for (int at = q.indexOf(w); at >= 0; at = q.indexOf(w, at + 1)) {
            int end = at + w.length();
            boolean before = at > 0 && Character.UnicodeScript.of(q.codePointBefore(at)) == Character.UnicodeScript.HAN;
            boolean after = end < q.length() && (Character.UnicodeScript.of(q.codePointAt(end)) == Character.UnicodeScript.HAN || Character.UnicodeScript.of(q.codePointAt(end)) == Character.UnicodeScript.KATAKANA);
            if (!before && !after) return true;
        }
        return false;
    }

    /** Whether the phrase speaks of the word as a family: 遠藤家, the Endo family, the house of Hale, the Endos. */
    static boolean familyAround(String word, String quote) { return familyPhrase(word, quote) != null; }

    /** The words with which the phrase speaks of the word as a family ("遠藤家", "the Endo family", "the Endos"); null when it does not. */
    static String familyPhrase(String word, String quote) {
        if (word == null || word.isBlank() || quote == null) return null;
        String q = norm(quote), w = Pattern.quote(norm(word));
        List<Pattern> ways = FamilyForms.script(word).equals("latin")
                ? List.of(Pattern.compile("(?i)(?:\\bthe\\s+)?(?<!\\p{L})" + w + "\\s+(?:family|house|line|clan|dynasty)\\b"), Pattern.compile("(?i)\\bhouse\\s+of\\s+" + w + "(?!\\p{L})"),
                        Pattern.compile("(?i)\\bthe\\s+" + w + "(?:e?s)(?!\\p{L})"))
                : List.of(Pattern.compile(w + "(?:家|一族|一門|本家|分家|宗家)(?![族具屋政督])"));
        for (Pattern p : ways) { Matcher m = p.matcher(q); if (m.find()) return m.group().strip(); }
        return null;
    }

    /**
     * The family parts a library and a read know: those a source states (the family parts of the library's name claims and the names of its
     * families, {@link FamilyNameHistory.Index#familyParts}; the family parts the read gives: a person's {@code family}, a name's, a family it
     * speaks of, a family a membership names), and those worked out from where a word stands in names ({@link FamilyFolder#familyNames},
     * {@link #shared}) when the names never put that word in the other place and no source gives it as a given name. A Japanese name is written
     * either way round: "Morita Haru" beside "Haru Morita" says nothing of which word is the family name, and a guess would make the given name
     * Haru a family name.
     */
    static Set<String> familyParts(Graph g, FamilyAccount.Read read) {
        Set<String> out = new LinkedHashSet<>(FamilyNameHistory.of(g).familyParts());
        Set<String> guessed = new LinkedHashSet<>();
        try { guessed.addAll(FamilyFolder.everyFamilyName(g)); } catch (RuntimeException e) { }
        if (read != null) {
            out.addAll(personFamilyParts(null, read));
            for (FamilyAccount.Fact f : read.facts()) if (f.relation().equals(FamilyHouses.MEMBER) && FamilyHouses.familyWord(f.object())) { String n = FamilyHouses.familyName(f.object()); if (properName(n) && !n.equals(f.object().strip())) out.add(n); }
            guessed.addAll(shared(fullNames(read)));
        }
        Set<String> eitherWay = bothWaysRound(g, read), givens = givenParts(g, read);
        for (String w : guessed) {
            if (FamilyForms.script(w).equals("latin") && eitherWay.contains(FamilyForms.latinKey(w))) continue;
            if (givens.stream().anyMatch(gv -> FamilyForms.sameForm(gv, w))) continue;
            out.add(w);
        }
        return out;
    }

    /** The words in Latin letters that stand first in one name of the library or the read and last in another: names written either way round. */
    private static Set<String> bothWaysRound(Graph g, FamilyAccount.Read read) {
        Set<String> names = new LinkedHashSet<>();
        if (g != null) for (Graph.Node n : g.nodes()) if ("person".equals(n.kind())) { names.add(n.label()); names.addAll(n.aliases()); }
        if (read != null) names.addAll(fullNames(read));
        Set<String> first = new LinkedHashSet<>(), last = new LinkedHashSet<>();
        for (String n : names) {
            if (n == null || !FamilyForms.script(n).equals("latin")) continue;
            String[] w = n.replaceAll("\\s*[(（][^)）]*[)）]\\s*$", "").replace(",", " ").strip().split("\\s+");
            if (w.length < 2) continue;
            first.add(FamilyForms.latinKey(w[0]));
            last.add(FamilyForms.latinKey(w[w.length - 1]));
        }
        first.retainAll(last);
        return first;
    }

    /** The given parts a source states: the {@code given} of the read's people and names, and the given parts of the library's name claims. */
    private static Set<String> givenParts(Graph g, FamilyAccount.Read read) {
        Set<String> out = new LinkedHashSet<>();
        if (g != null) {
            FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
            for (Graph.Node n : g.nodes()) if ("person".equals(n.kind())) for (FamilyNameHistory.Name x : idx.names(n.id())) if (!x.implicit() && !x.given().isBlank()) out.add(x.given());
        }
        if (read != null) {
            for (FamilyAccount.Person p : read.people()) if (!p.given().isBlank()) out.add(p.given());
            for (FamilyAccount.NameRead n : read.names()) if (!n.given().isBlank()) out.add(n.given());
        }
        return out;
    }

    /**
     * The family names people carry, as a source states them: the family parts of the library's name claims (a family's own name is not
     * among them), the {@code family} the read gives a person or a name, and the families the read speaks of as families, whose words were
     * checked. Beside such a name, "the Hales" and "the Takahashi line" are a family. {@code g} may be null: the read's alone.
     */
    static Set<String> personFamilyParts(Graph g, FamilyAccount.Read read) {
        Set<String> out = new LinkedHashSet<>();
        if (g != null) {
            FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
            for (Graph.Node n : g.nodes()) if ("person".equals(n.kind())) for (FamilyNameHistory.Name x : idx.names(n.id())) if (!x.implicit() && !x.family().isBlank()) out.add(x.family());
        }
        if (read != null) {
            for (FamilyAccount.Person p : read.people()) if (!p.family().isBlank()) out.add(p.family());
            for (FamilyAccount.NameRead n : read.names()) if (!n.family().isBlank()) out.add(n.family());
            for (FamilyAccount.FamilyRead f : read.families()) for (String w : List.of(f.name(), f.written())) { String n = FamilyHouses.familyName(w.replaceAll("\\s*[(（][^)）]*[)）]\\s*$", "")); if (properName(n)) out.add(n); }
        }
        return out;
    }

    /** Words that stand before a family word and are no family's name: an article, a pronoun. */
    private static final Pattern NOT_A_NAME = Pattern.compile("(?i)(?:the|a|an|his|her|their|our|my|its|this|that|these|those|whose|which|one|each|every)(?:\\s.*)?");

    /** Whether the name a family word stands beside is a name: in Latin letters it begins with a capital and is no article or pronoun ("the family" names none). */
    static boolean properName(String name) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty()) return false;
        String sc = FamilyForms.script(n);
        if (!sc.equals("latin")) return !sc.isEmpty();
        // a capital in any of its words: a name may begin with a small word (van Hale, de Moore, ten Hart)
        return n.matches(".*\\p{Lu}.*") && !NOT_A_NAME.matcher(n).matches();
    }

    /** A family named by 家 and its like after its name in characters (森田家), or as "the <Name> family": the words name a family by themselves. */
    private static final Pattern NAMED_HAN = Pattern.compile("^\\p{IsHan}{1,4}(?:家|一族|一門|本家|分家|宗家)$");
    /** The small words a family name may begin with (van Hale, de Moore, ten Hart): defaults behind the rule that a name has a capital. */
    private static final String PARTICLES = "(?:(?:van|von|de|da|di|del|della|der|den|des|du|dos|das|ten|ter|le|la|zu|af|bin|ibn|ap)\\s+){0,2}(?:(?:al|el)-)?";
    private static final Pattern NAMED_LATIN = Pattern.compile("^(?:[Tt]he\\s+)?" + PARTICLES + "\\p{Lu}[\\p{L}'’-]*(?:\\s+\\p{Lu}[\\p{L}'’-]*)?\\s+family$");

    /**
     * Whether words that carry a family word name a family: 森田家 and "the Morita family" do by themselves; "the house of X", "the Xs", "the X
     * line" or "the X clan" do when X is a family part a person carries ({@code personParts}), because a parliament, a religious society, a band
     * or a railway is written the same way. Words whose family word stands beside no name ("the family") name none.
     */
    static boolean namesAFamily(String written, Set<String> personParts) {
        String w = written == null ? "" : written.strip().replaceAll("\\s*[(（][^)）]*[)）]\\s*$", "").strip();
        // the family name the words give, of a branch too (森田分家 is of 森田), with an English plural's s, or es, off (the Hales, Hale)
        String name = FamilyHouses.nameOf(w);
        if (!properName(name) || name.equals(w)) return false;
        // the words carry a word for a family: by themselves (森田家, the Morita family), or in a form that names a family only when its name is one
        if (!FamilyHouses.familyWord(w) && !FamilyHouses.familyWord(w, List.of(name))) return false;
        if (NAMED_HAN.matcher(w).matches() || NAMED_LATIN.matcher(w).matches()) return true;
        if (personParts == null) return false;
        return known(name, personParts);
    }

    /**
     * Whether a party of a fact is a family: the library holds it as a family, the read speaks of it as a family in these words, or the words
     * name a family ({@link #namesAFamily}). A family is never a person: a relation to it is a membership of it ({@link #membership}).
     */
    static boolean familyParty(Graph g, String party, Collection<String> readFamilies, Set<String> personParts) {
        String p = party == null ? "" : party.strip();
        if (p.isEmpty() || isMention(p) || FamilyQuestions.placeholder(p)) return false;
        if (g != null && FamilyHouses.isFamily(g, g.nodeIdOf(p))) return true;
        if (readFamilies != null) for (String rf : readFamilies)
            if (rf != null && !rf.isBlank() && FamilyAccount.flat(rf).equals(FamilyAccount.flat(p)) && !FamilyHouses.familyName(rf).equals(rf.strip())) return true;
        return namesAFamily(p, personParts);
    }

    /** The relations whose other party may be a family as a whole: the household a head heads, the adopter, the one an heir follows. */
    private static final Set<String> OF_A_HOUSEHOLD = Set.of("head-of-household", "adopted-by", "heir-of");

    /**
     * Whether the other party of such a relation ({@link #OF_A_HOUSEHOLD}), written as a family name alone (森田, Morita), is the name of a
     * family the read speaks of (its name, or the name in its words, in any form: Morita for the family 森田 the read writes "the Morita family")
     * or a family the library holds ({@link FamilyHouses#named}): 森田勇 | head-of-household | 森田 is the head of that family, never of a person.
     */
    static boolean familyByName(Graph g, FamilyAccount.Read read, String relation, String party) {
        if (!OF_A_HOUSEHOLD.contains(relation)) return false;
        String w = word(party);
        if (w == null || !FamilyHouses.familyName(w).equals(w)) return false;
        if (read != null) for (FamilyAccount.FamilyRead f : read.families()) {
            boolean jp = FamilyForms.japanese(f.name()) || FamilyForms.japanese(f.written());
            for (String n : List.of(f.name(), FamilyHouses.familyName(f.written()))) if (!n.isBlank() && FamilyForms.sameForm(n, w, jp)) return true;
        }
        return g != null && !FamilyHouses.named(g, w).isEmpty();
    }

    /** The ways the read writes the families it speaks of: each family's name and its words (森田家, the Morita family). */
    static List<String> familiesWritten(FamilyAccount.Read read) {
        List<String> out = new ArrayList<>();
        if (read == null) return out;
        for (FamilyAccount.FamilyRead f : read.families()) { if (!f.written().isBlank()) out.add(f.written()); }
        return out;
    }

    /**
     * A relation with a family for one party ("森田勇 | head-of-household | 森田家", "森田正二 | adopted-by | 髙橋家", heir-of the Takahashi
     * family): the other party's membership of the family, how they came in and their role as the relation and the words give them. A head of
     * the family is its head; an adoption into it is an entry by adoption, as its heir where the words say heir; an heir of it is its heir, by
     * adoption where the words say adopted; a child of it was born into it; a marriage into it is by marriage; anything else is a membership whose
     * way in is not stated. {@code familySubject}: which side is the family.
     */
    static FamilyAccount.Fact membership(FamilyAccount.Fact f, boolean familySubject) {
        String person = familySubject ? f.object() : f.subject(), family = familySubject ? f.subject() : f.object();
        String q = f.quote() == null ? "" : f.quote();
        Map<String, String> d = new LinkedHashMap<>();
        for (String k : List.of("how", "left", "role", "from", "to")) if (f.detail().containsKey(k)) d.put(k, f.detail().get(k));
        String rel = f.relation();
        // heir from the adoption's own kind, which the words were checked for, or from an heir word beside the family's own words
        boolean heirWords = "heir".equals(f.detail().get("kind")) || heirBeside(q, family);
        switch (rel) {
            case "head-of-household" -> { if (!familySubject) d.put("role", "head"); }
            case "adopted-by" -> { if (!familySubject) { d.putIfAbsent("how", "mukoyoshi".equals(f.detail().get("kind")) ? "mukoyoshi" : "adoption"); if (heirWords) d.putIfAbsent("role", "heir"); } }
            case "heir-of" -> { if (!familySubject) { d.put("role", "heir"); if (FamilyNameHistory.saysKind(q, "adoptive")) d.putIfAbsent("how", "adoption"); } }
            default -> { }
        }
        d.putIfAbsent("how", membershipHow(familySubject ? flip(rel) : rel));
        return new FamilyAccount.Fact(person, FamilyHouses.MEMBER, family, f.date(), f.quote(), d);
    }

    /**
     * Whether an heir word stands in the same part of the quote as the family's own words: "adopted as heir into the Takahashi family (髙橋家)"
     * says heir of that family; "adopted into the Takahashi family (髙橋家); the heir of the Morita family was his brother" does not. The parts
     * are cut at a full stop, a semicolon and a comma.
     */
    static boolean heirBeside(String quote, String family) {
        if (quote == null || family == null || family.isBlank()) return false;
        List<String> ways = new ArrayList<>(List.of(family.strip()));
        String name = FamilyHouses.familyName(family);
        if (!name.isBlank()) ways.add(name);
        for (String part : norm(quote).split("[;；。!?！？]|\\.(?=\\s|$)|,\\s*|、|，")) {
            String p = FamilyAccount.flat(part);
            if (ways.stream().anyMatch(w -> !FamilyAccount.flat(w).isEmpty() && p.contains(FamilyAccount.flat(w))) && FamilyAccount.HEIR_WORDS.matcher(part).find()) return true;
        }
        return false;
    }

    /** The relation read the other way round, for the ways a family comes into a membership: a family that is the parent of a person had the person as its child. */
    private static String flip(String relation) {
        return switch (relation) { case "parent-of" -> "child-of"; case "child-of" -> "parent-of"; default -> relation; };
    }

    /** The names of a read that are more than one word: its people, both sides of its relations, and the people its names belong to. */
    static List<String> fullNames(FamilyAccount.Read read) {
        Set<String> out = new LinkedHashSet<>();
        for (FamilyAccount.Person p : read.people()) { out.add(p.name()); out.addAll(p.also()); }
        for (FamilyAccount.Fact f : read.facts()) { out.add(f.subject()); if (FamilyAccount.personToPerson(f.relation()) || FamilyAccount.associate(f.relation())) out.add(f.object()); }
        for (FamilyAccount.NameRead n : read.names()) { out.add(n.person()); out.add(n.name()); }
        List<String> whole = new ArrayList<>();
        for (String n : out) {
            if (n == null || n.isBlank() || FamilyQuestions.placeholder(n)) continue;
            String b = n.replaceAll("\\s*[(（][^)）]*[)）]\\s*$", "").strip();
            if (full(b) && !whole.contains(b)) whole.add(b);
        }
        return whole;
    }

    /** Whether a name is more than a family name or a given name alone: two words or more, or three characters or more written without a space. */
    static boolean full(String name) {
        String b = name == null ? "" : name.strip();
        return !b.isEmpty() && (word(b) == null || !FamilyForms.script(b).equals("latin") && b.codePointCount(0, b.length()) >= 3);
    }

    /**
     * The family names two names share, as {@link FamilyFolder#familyNames} works them out for a library: in Latin letters a last word two
     * names carry (a first word counts when it is also somebody's last word, since a Japanese name is written either way round); in
     * characters the longest beginning of two characters or more that two names share.
     */
    static Set<String> shared(List<String> names) {
        Set<String> out = new LinkedHashSet<>();
        Map<String, Integer> last = new HashMap<>(), first = new HashMap<>();
        Map<String, String> written = new HashMap<>();
        List<String> joined = new ArrayList<>();
        for (String n : new LinkedHashSet<>(names)) {
            if (FamilyForms.script(n).equals("latin")) {
                String[] w = n.replace(",", " ").strip().split("\\s+");
                if (w.length < 2) continue;
                String l = FamilyForms.latinKey(w[w.length - 1]), f = FamilyForms.latinKey(w[0]);
                last.merge(l, 1, Integer::sum); written.putIfAbsent(l, w[w.length - 1]);
                first.merge(f, 1, Integer::sum); written.putIfAbsent(f, w[0]);
            } else if (FamilyForms.script(n).equals("han")) joined.add(FamilyForms.hanKey(n));
        }
        last.forEach((w, c) -> { if (c + first.getOrDefault(w, 0) >= 2) out.add(written.get(w)); });
        for (String a : new LinkedHashSet<>(joined)) {
            String best = "";
            for (String b : new LinkedHashSet<>(joined)) {
                if (a.equals(b)) continue;
                int k = 0;
                while (k < a.length() && k < b.length() && a.charAt(k) == b.charAt(k)) k++;
                if (k >= 2 && k < a.length() && k > best.length()) best = a.substring(0, k);
            }
            if (!best.isEmpty()) out.add(best);
        }
        return out;
    }

    /** Whether a word is one of the family parts ({@link #familyParts}), in its own script. */
    static boolean known(String word, Set<String> parts) {
        for (String p : parts) if (p != null && !p.isBlank() && FamilyForms.sameForm(word, p.strip())) return true;
        return false;
    }

    /** The relations a person written only as a family name has, and nothing more: they do not make it somebody the library knows by that one name. */
    private static final Set<String> ONLY_AS_A_RELATIVE = Set.of("child-of", "parent-of", "adopted-by", "step-parent-of", "foster-child-of", "married-to", "sibling-of", "relative-of",
            "parent-in-law-of", "heir-of", "head-of-household", "sex", "is filed under", "mentions", FamilyHouses.MEMBER);

    /**
     * What a party of a relation is, by the words: "family" when the phrase writes the word only as a family (遠藤家, the Endo family), "person"
     * when it is somebody known only by a family name ("Endo's son"; "Endo, the last head of the Endo family", where the word stands on its own
     * too), null when it is somebody's name. Both need the word to be the family part of a name the library or the read knows ({@code parts}),
     * and the party must not be a given name the read gives ({@code given}), the teller, or a person the library already knows something of by
     * that one name.
     */
    static String kind(Graph g, String party, String quote, boolean marked, String teller, Set<String> parts, Set<String> given) {
        String w = word(party);
        if (w == null) return null;
        if (teller != null && !teller.isBlank() && (FamilyAccount.flat(teller).equals(FamilyAccount.flat(w)) || teller.strip().toLowerCase(Locale.ROOT).startsWith(w.toLowerCase(Locale.ROOT) + " "))) return null;
        if (given != null) for (String gv : given) if (FamilyForms.sameForm(gv, w)) return null;
        Graph.Node held = g.node(g.nodeIdOf(party));
        if (held != null && "person".equals(held.kind()))
            for (Graph.Edge e : g.edges()) if ((e.from().equals(held.id()) || e.to().equals(held.id())) && !ONLY_AS_A_RELATIVE.contains(e.predicate()) && !FamilyKin.gone(e)) return null;
        if (!known(w, parts)) return null;
        boolean family = familyAround(w, quote);
        if (family && !standsAlone(w, quote)) return "family";
        if (family || marked || possessive(w, quote)) return "person";
        return null;
    }

    /**
     * Whether a party written by one word is somebody the words write by a family name ({@link #kind} "person"), though the library holds the
     * word as the name of a family: "Endo's son" is Endo's son, also after the family answered that an entry "Endo" is the Endo family itself.
     */
    static boolean writtenAsSomebody(Graph g, String party, String quote, boolean marked, String teller, Set<String> parts, Set<String> given) {
        return word(party) != null && !namesAFamily(party, Set.of()) && "person".equals(kind(g, party, quote, marked, teller, parts, given));
    }

    /** The relation a family written as a party of a person's relation stands for: that person's membership of the family, and how they came in. */
    static String membershipHow(String relation) {
        return switch (relation) {
            case "child-of" -> "birth";
            case "adopted-by" -> "adoption";
            case "married-to" -> "marriage";
            default -> "unstated";
        };
    }

    /**
     * The word for who a described person is to the person named beside them, from the relation and which side the described person is on,
     * with the sex only where the quote says it: father or mother, else parent; son or daughter, else child; husband or wife; brother or
     * sister. A husband's, a wife's, a brother's or a sister's word describes the party it stands beside: "妻 遠藤" and "Tom's wife, Ellis" make
     * the one written only by the family name the wife; "遠藤の妻" and "Endo's wife" make the other party the wife, and so the one written only
     * by the family name her husband; "遠藤の弟" says nothing of whether Endo is a brother or a sister.
     */
    static String role(String relation, boolean mentionIsObject, String subject, String object, String quote) {
        String q = norm(quote == null ? "" : quote);
        String[] sex = FamilyKin.sexFromQuote(relation, subject, object, q);
        String mention = mentionIsObject ? object : subject;
        String sexOf = sex != null && sex[0].equals(mention) ? sex[1] : "";
        boolean parentSide = relation.equals("child-of") ? mentionIsObject : relation.equals("parent-of") && !mentionIsObject;
        String w = word(mention);
        return switch (relation) {
            case "child-of", "parent-of" -> parentSide ? (sexOf.equals("male") ? "father" : sexOf.equals("female") ? "mother" : "parent") : (sexOf.equals("male") ? "son" : sexOf.equals("female") ? "daughter" : "child");
            case "adopted-by" -> mentionIsObject ? "parent by adoption" : "adopted child";
            case "married-to" -> {
                String own = kinOf(w, q, SPOUSE_WORDS, true), other = kinOf(w, q, SPOUSE_WORDS, false);
                yield own != null ? own : other != null ? (other.equals("husband") ? "wife" : "husband") : "husband or wife";
            }
            case "sibling-of" -> { String own = kinOf(w, q, SIBLING_WORDS, true); yield own != null ? own : "brother or sister"; }
            case "step-parent-of" -> mentionIsObject ? "stepchild" : "step-parent";
            case "foster-child-of" -> mentionIsObject ? "foster parent" : "foster child";
            case "parent-in-law-of" -> mentionIsObject ? "son-in-law or daughter-in-law" : "parent-in-law";
            case "heir-of" -> mentionIsObject ? "predecessor" : "heir";
            case "head-of-household" -> mentionIsObject ? "household member" : "head of household";
            case "informant-for" -> mentionIsObject ? "subject of a record" : "informant";
            case "witness-for" -> mentionIsObject ? "subject of a record" : "witness";
            case "godparent-of" -> mentionIsObject ? "godchild" : "godparent";
            default -> "relative";
        };
    }

    /** The husband's and wife's words, and the brother's and sister's, each with the role it gives: {Latin words, words in characters, role}. */
    private static final String[][] SPOUSE_WORDS = {{"husband", "夫(?!人)|婿", "husband"}, {"wife|widow", "妻|嫁|夫人", "wife"}};
    private static final String[][] SIBLING_WORDS = {{"brother", "兄|弟", "brother"}, {"sister", "姉|妹", "sister"}};

    /**
     * The role a kin word gives, when one stands beside the word the party is written by: {@code own} the word that describes the party itself
     * ("wife, Ellis", "妻 遠藤", "Hale (brother)"), else the word the party's possessive puts on the other party ("Endo's wife", 遠藤の妻, "the wife
     * of Endo"). Null when no such word stands there, or when words of two roles do.
     */
    private static String kinOf(String word, String quote, String[][] kinds, boolean own) {
        if (word == null || word.isBlank() || quote == null) return null;
        String w = Pattern.quote(norm(word));
        boolean latin = FamilyForms.script(word).equals("latin");
        String found = null;
        for (String[] k : kinds) {
            String kin = latin ? k[0] : k[1];
            List<Pattern> ps = latin
                    ? own ? List.of(Pattern.compile("(?i)\\b(?:" + kin + ")\\b\\s*[,:：]?\\s*(?:the\\s+)?" + w + "(?!\\p{L})"), Pattern.compile("(?i)(?<!\\p{L})" + w + "\\s*[(（]\\s*(?:" + kin + ")\\s*[)）]"),
                                  Pattern.compile("(?i)(?<!\\p{L})" + w + "\\s*,\\s*(?:his|her|their|the)\\s+(?:\\p{L}+\\s+)?(?:" + kin + ")\\b"))
                          : List.of(Pattern.compile("(?i)(?<!\\p{L})" + w + "['’]s?\\s+" + ORDINAL + "(?:" + kin + ")\\b"), Pattern.compile("(?i)\\b(?:" + kin + ")\\s+of\\s+(?:the\\s+)?(?:late\\s+)?" + w + "(?!\\p{L})"))
                    : own ? List.of(Pattern.compile("(?:" + kin + ")\\s*[:：、,]?\\s*" + w), Pattern.compile(w + "\\s*[(（]\\s*(?:" + kin + ")\\s*[)）]"))
                          : List.of(Pattern.compile(w + "(?:氏|さん|様|殿)?\\s*[の之]\\s*(?:" + kin + ")"));
            for (Pattern p : ps) if (p.matcher(quote).find()) { if (found != null && !found.equals(k[2])) return null; found = k[2]; }
        }
        return found;
    }

    /** How many generations above the person named beside it a described person of this role stands: 1 for a parent, -1 for a child, 0 for a husband, a wife, a brother or a sister; null when the role does not say. */
    static Integer generation(String role) {
        String r = role == null ? "" : role.toLowerCase(Locale.ROOT);
        if (r.matches("father|mother|parent|parent by adoption|step-parent|foster parent|parent-in-law")) return 1;
        if (r.matches("son|daughter|child|adopted child|stepchild|foster child|son-in-law or daughter-in-law")) return -1;
        if (r.matches("husband|wife|husband or wife|brother|sister|brother or sister")) return 0;
        return null;
    }

    /**
     * A relation read with one party written only by a family name, as it is filed: a family is the other party's membership of that family
     * ({@code member-of}, how they came in by the relation); a person known only by the family name is the described person
     * ({@link #placeholder}), with the nearest earlier full name of the passage kept as a candidate. Null when neither party is either.
     */
    static FamilyAccount.Fact described(Graph g, FamilyAccount.Fact f, String teller, Set<String> parts, Set<String> given) {
        List<FamilyAccount.Fact> all = describedAll(g, f, teller, parts, given);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * The same, with every fact the words give: where the kin word stands on the family itself ("the second son of the Endo family", 遠藤家の次男),
     * or the words speak of the family beside somebody written by its name ("Endo's son … born into the Endo family"), the other party's
     * membership of the family and, after it, the relation to the described person of that family who is the other party
     * ({@link #kinOfTheFamily}), with the model's reading kept as a candidate. Empty when neither party is written only by a family name.
     */
    static List<FamilyAccount.Fact> describedAll(Graph g, FamilyAccount.Fact f, String teller, Set<String> parts, Set<String> given) {
        if (!(FamilyAccount.personToPerson(f.relation()) || FamilyAccount.associate(f.relation()))) return List.of();
        boolean marked = f.detail().containsKey(MARKED);
        String sKind = kind(g, f.subject(), f.quote(), marked, teller, parts, given), oKind = kind(g, f.object(), f.quote(), marked, teller, parts, given);
        if (sKind != null && oKind != null) return List.of();   // both written only by a family name: nobody to hang it on, and it stays as written
        if (sKind == null && oKind == null) return List.of();
        boolean objectSide = oKind != null;
        String kind = objectSide ? oKind : sKind, party = objectSide ? f.object() : f.subject(), other = objectSide ? f.subject() : f.object();
        // the other party is who the described person is written beside: a name, or somebody described without an apostrophe of their own
        if (isMention(other) || FamilyQuestions.placeholder(other) && other.matches(".*['’]s .*")) return List.of();
        String w = word(party);
        Map<String, String> detail = new LinkedHashMap<>(f.detail());
        detail.remove(MARKED);
        String role = role(f.relation(), objectSide, f.subject(), f.object(), f.quote());
        String p = placeholder(other, role, w);
        FamilyAccount.Fact person = new FamilyAccount.Fact(objectSide ? f.subject() : p, f.relation(), objectSide ? p : f.object(), f.date(), f.quote(), detail);
        // somebody written by the family name, and the words speak of the family too ("Kenji, Endo's second son, was born into the Endo
        // family"): the other party's membership as well
        boolean family = kind.equals("family");
        if (!family && !familyAround(w, f.quote())) return List.of(person);
        Map<String, String> entry = new LinkedHashMap<>(detail);
        entry.remove(NEAR); entry.remove(PICKED);
        String how = membershipHow(objectSide ? f.relation() : flip(f.relation()));
        String kinWords = kinOfTheFamilyWords(w, f.quote(), f.relation());
        boolean kin = kinWords != null;
        // "the adopted son of the Endo family" says no birth into it
        if (kin && how.equals("birth") && NOT_BY_BIRTH.matcher(kinWords).find()) how = "unstated";
        entry.putIfAbsent("how", how);
        String written = familyPhrase(w, f.quote());
        FamilyAccount.Fact membership = new FamilyAccount.Fact(other, FamilyHouses.MEMBER, written == null ? w : written, f.date(), f.quote(), entry);
        return kin || !family || detail.containsKey(PICKED) ? List.of(membership, person) : List.of(membership);
    }

    /** The detail key for the person the model took a party written only by a family name for: a candidate, offered first, never the link by itself. */
    static final String PICKED = "picked";

    /**
     * A relation whose party the model wrote by a full name that its words do not carry: the quote writes neither that name, in any of its forms,
     * nor its given part, only its family part ("Endo's son, Morita Kenji", read as 遠藤正一's son). The words decide who a party is, so the party
     * is written as the words write it ("Endo"), marked as written only by a family name, and the model's reading is kept as the candidate it
     * is ({@link #PICKED}). Null when the words carry the party's name or its given part, when they carry no family part of it, or when the part
     * they carry stands only inside another person's name. {@code parts}: the family parts the library and the read know; the word found is
     * added to them.
     */
    static FamilyAccount.Fact familyPartOnly(Graph g, FamilyAccount.Fact f, FamilyAccount.Read read, Set<String> parts) {
        if (!(FamilyAccount.personToPerson(f.relation()) || FamilyAccount.associate(f.relation())) || f.quote() == null || f.quote().isBlank()) return null;
        for (boolean objectSide : new boolean[]{true, false}) {
            String party = objectSide ? f.object() : f.subject(), other = objectSide ? f.subject() : f.object();
            if (party == null || party.isBlank() || !full(party) || isMention(party) || FamilyQuestions.placeholder(party) || isMention(other)) continue;
            List<String> own = namesOf(read, party);
            boolean named = false;
            // a family name alone among the party's other spellings ("Endo" beside Endō Shōichi) writes the family, not the party
            for (String o : own) if (word(o) == null || !known(o, parts)) for (String form : FamilyAccount.writtenForms(o, List.of())) if (FamilyAccount.flat(form).length() >= 2 && FamilyAccount.flat(f.quote()).contains(FamilyAccount.flat(form))) named = true;
            if (named) continue;
            // the words with every other person of the read taken out: a family part inside another's name is that person's
            String q = norm(f.quote());
            List<String> others = new ArrayList<>();
            for (String n : allNames(read)) if (own.stream().noneMatch(o -> FamilyAccount.flat(o).equals(FamilyAccount.flat(n)))) others.add(n);
            if (full(other)) others.add(other);
            others.sort((a, b) -> b.length() - a.length());
            for (String o : others) for (String form : FamilyAccount.writtenForms(o, List.of())) if (norm(form).strip().length() >= 2) q = q.replace(norm(form), " | ");
            String[] fg = familyAndGiven(g, read, party, own, parts);
            boolean givenThere = false;
            for (String gv : fg[1].isEmpty() ? List.<String>of() : List.of(fg[1].split("\t"))) if (standsIn(gv, q) != null) givenThere = true;
            if (givenThere || fg[0].isEmpty()) continue;
            String w = null;
            for (String fam : fg[0].split("\t")) { w = standsIn(fam, q); if (w != null) break; }
            // the family part must stand for the party: "Endo's son", 遠藤の子, or a party the model marks as written only by a family name. The
            // family named as a family ("entered the Morita family") or a thing named for it ("the Morita shop") is no party, but for a parent
            // the words give only as the family the child was born into ("Kenji was born into the Endo family"): somebody of that family
            boolean parent = objectSide ? f.relation().equals("child-of") : f.relation().equals("parent-of");
            if (w == null || !(kinFits(w, q, f.relation()) || f.detail().containsKey(MARKED) && familyPhrase(w, q) == null || parent && familyPhrase(w, q) != null)) continue;
            parts.add(w);
            Map<String, String> d = new LinkedHashMap<>(f.detail());
            d.put(MARKED, "true");
            d.put(PICKED, party);
            return new FamilyAccount.Fact(objectSide ? f.subject() : w, f.relation(), objectSide ? w : f.object(), f.date(), f.quote(), d);
        }
        return null;
    }

    /** The kin words of each kind of relation, in Latin letters and in characters: {Latin, characters}. */
    private static final Map<String, String[]> KIN_OF = Map.of(
            "parent", new String[]{"sons?|daughters?|child|children|boy|girl|father|mother|parents?|step(?:son|daughter|father|mother)", "息子|娘|長男|次男|二男|三男|四男|五男|長女|次女|二女|三女|四女|末子|養子|養女|子|父|母"},
            "spouse", new String[]{"wife|husband|widow(?:er)?", "妻|夫(?!人)|夫人|嫁|婿|未亡人"},
            "sibling", new String[]{"brothers?|sisters?", "兄|弟|姉|妹"},
            "in-law", new String[]{"son-in-law|daughter-in-law|father-in-law|mother-in-law", "婿|嫁|舅|姑|義父|義母"},
            "heir", new String[]{"heirs?", "跡継ぎ|跡取り|後継ぎ"});

    /** The kind of kin word a relation is said with; null for any. */
    private static String kinKind(String relation) {
        return switch (relation) {
            case "parent-of", "child-of", "adopted-by", "step-parent-of", "foster-child-of" -> "parent";
            case "married-to" -> "spouse";
            case "sibling-of" -> "sibling";
            case "parent-in-law-of" -> "in-law";
            case "heir-of" -> "heir";
            default -> null;
        };
    }

    /**
     * Whether the words put the family name in a phrase of kinship that fits the relation: "Endo's son" or 遠藤の子 for a parent and a child,
     * "Endo's wife" for a marriage, "Father: Hale" in a table. "Endo's daughter married Morita Kenji" writes the wife by her father, not by
     * the family name, and does not fit a marriage.
     */
    static boolean kinFits(String word, String quote, String relation) {
        if (!possessive(word, quote)) return false;
        String kind = kinKind(relation);
        if (kind == null) return true;
        String q = norm(quote), w = Pattern.quote(norm(word));
        String[] k = KIN_OF.get(kind);
        if (FamilyForms.script(word).equals("latin"))
            return Pattern.compile("(?i)(?<!\\p{L})" + w + "['’]s?\\s+" + ORDINAL + "(?:" + k[0] + ")\\b").matcher(q).find()
                    || Pattern.compile("(?i)\\b(?:" + k[0] + ")\\s+of\\s+(?:the\\s+)?(?:late\\s+)?" + w + "(?!\\p{L})").matcher(q).find()
                    || Pattern.compile("(?i)\\b(?:" + k[0] + ")\\s*[:：|]\\s*" + w + "(?!\\p{L})").matcher(q).find()
                    || Pattern.compile("(?i)(?<!\\p{L})" + w + "\\s*[(（]\\s*(?:" + k[0] + ")\\s*[)）]").matcher(q).find();
        return Pattern.compile(w + HOUSE_JA + "(?:氏|さん|様|殿)?\\s*[の之]\\s*(?:" + k[1] + ")").matcher(q).find()
                || Pattern.compile("(?:^|[\\s|｜（(・:：、])(?:" + k[1] + ")\\s*[:：|｜]?\\s*" + w + "(?![\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}])").matcher(q).find()
                || Pattern.compile(w + "\\s*[(（]\\s*(?:" + k[1] + ")\\s*[)）]").matcher(q).find();
    }

    /** Every way the read writes its people: their names, other spellings and readings, both sides of its relations, and the names it gives them. */
    private static List<String> allNames(FamilyAccount.Read read) {
        Set<String> out = new LinkedHashSet<>();
        for (FamilyAccount.Person p : read.people()) { out.add(p.name()); out.addAll(p.also()); if (!p.reading().isBlank()) out.add(p.reading()); }
        for (FamilyAccount.Fact x : read.facts()) if (FamilyAccount.personToPerson(x.relation()) || FamilyAccount.associate(x.relation())) { out.add(x.subject()); out.add(x.object()); }
        for (FamilyAccount.NameRead n : read.names()) { out.add(n.person()); out.add(n.name()); out.addAll(n.forms()); }
        out.removeIf(n -> n == null || n.isBlank() || !full(n) || isMention(n) || FamilyQuestions.placeholder(n));
        return new ArrayList<>(out);
    }

    /** The ways the read writes one person: the name, the other spellings and the reading it gives, and the names it gives them with their forms. */
    private static List<String> namesOf(FamilyAccount.Read read, String name) {
        Set<String> out = new LinkedHashSet<>(List.of(name));
        for (FamilyAccount.Person p : read.people()) if (p.name().equals(name)) { out.addAll(p.also()); if (!p.reading().isBlank()) out.add(p.reading()); }
        for (FamilyAccount.NameRead n : read.names()) if (n.person().equals(name)) { out.add(n.name()); out.addAll(n.forms()); }
        return new ArrayList<>(out);
    }

    /**
     * A person's family parts and given parts, each list joined by tabs, as the read and the library state them: the parts the read gives; the
     * parts of the library's name claims of the person; in Latin letters the words of a form that are family parts a source states, the other
     * words given names; in characters the beginning that is such a family part, the rest the given name.
     */
    private static String[] familyAndGiven(Graph g, FamilyAccount.Read read, String name, List<String> forms, Set<String> parts) {
        Set<String> fam = new LinkedHashSet<>(), given = new LinkedHashSet<>();
        for (FamilyAccount.Person p : read.people()) if (p.name().equals(name)) { if (!p.family().isBlank()) fam.add(p.family()); if (!p.given().isBlank()) given.add(p.given()); }
        for (FamilyAccount.NameRead n : read.names()) if (n.person().equals(name)) { if (!n.family().isBlank()) fam.add(n.family()); if (!n.given().isBlank()) given.add(n.given()); }
        if (g != null && g.node(g.nodeIdOf(name)) != null)
            for (FamilyNameHistory.Name x : FamilyNameHistory.of(g).names(g.nodeIdOf(name))) if (!x.implicit()) { if (!x.family().isBlank()) fam.add(x.family()); if (!x.given().isBlank()) given.add(x.given()); }
        Set<String> known = new LinkedHashSet<>(parts);
        known.addAll(fam);
        for (String form : forms) {
            String sc = FamilyForms.script(form);
            if (sc.equals("latin")) {
                String[] ws = form.replace(",", " ").strip().split("\\s+");
                List<String> f = new ArrayList<>(), rest = new ArrayList<>();
                for (String x : ws) (known(x, known) ? f : rest).add(x);
                if (!f.isEmpty() && !rest.isEmpty()) { fam.addAll(f); given.addAll(rest); }
            } else if (sc.equals("han") || sc.equals("kana")) {
                String split = FamilyAccount.givenBySplit(form, known);
                if (!split.isEmpty()) { given.add(split); fam.add(form.replaceAll("[\\s　・]+", "").substring(0, form.replaceAll("[\\s　・]+", "").length() - split.length())); }
            }
        }
        return new String[]{String.join("\t", fam), String.join("\t", given)};
    }

    /**
     * A part of a name as it stands in words, in its own script and as a word of its own: "Endo" in "Endo's son" for Endō, 遠藤 in 遠藤の子.
     * Null when it does not stand there.
     */
    private static String standsIn(String part, String words) {
        String p = part == null ? "" : part.strip();
        if (p.isEmpty() || words == null) return null;
        String sc = FamilyForms.script(p);
        if (sc.equals("latin")) {
            Matcher m = Pattern.compile("\\p{IsLatin}[\\p{IsLatin}\\p{M}’'.-]*").matcher(words);
            while (m.find()) { String t = m.group().replaceAll("['’]s?$", "").replaceAll("[.-]+$", ""); if (!t.isEmpty() && FamilyForms.sameForm(t, p)) return t; }
            return null;
        }
        if (sc.equals("han")) {
            String key = FamilyForms.hanKey(p);
            for (int i = 0; i + p.length() <= words.length(); i++) { String t = words.substring(i, i + p.length()); if (FamilyForms.hanKey(t).equals(key)) return t; }
            return null;
        }
        if (sc.equals("kana")) return words.contains(p) ? p : null;
        return null;
    }

    /**
     * A fact of a life (a death, a home, a work) whose subject is written only by a family name: the same described person the read made for
     * that word beside a relation in the same words, when it made one; a family when the words speak of the family; otherwise somebody of
     * that family not identified yet ({@code <family>'s member (written only as W)}), whom the library tries to link and asks about as it does
     * every described person. Never a person named by the family name. Null when the subject is somebody's name.
     */
    static FamilyAccount.Fact lifeMention(Graph g, FamilyAccount.Fact f, String teller, Set<String> parts, Set<String> given, Map<String, List<String>> described) {
        if (FamilyAccount.personToPerson(f.relation()) || FamilyAccount.associate(f.relation()) || FamilyHouses.OF_A_FAMILY.contains(f.relation()) || f.relation().equals(FamilyHouses.MEMBER)
                || f.relation().equals(FamilyNameHistory.PREDICATE)) return null;
        String w = word(f.subject());
        if (w == null || full(f.subject())) return null;
        List<String> same = described.getOrDefault(FamilyAccount.flat(w) + "\t" + FamilyAccount.flat(f.quote()), List.of());
        boolean marked = f.detail().containsKey(MARKED) || described.keySet().stream().anyMatch(k -> k.startsWith(FamilyAccount.flat(w) + "\t"));
        String kind = kind(g, f.subject(), f.quote(), marked, teller, parts, given);
        if (kind == null) return null;
        Map<String, String> d = new LinkedHashMap<>(f.detail());
        d.remove(MARKED); d.remove(NEAR); d.remove(PICKED);
        String subject;
        if (kind.equals("family")) { String phrase = familyPhrase(w, f.quote()); subject = phrase == null ? w : phrase; }
        else if (same.size() == 1) subject = same.get(0);
        else {
            String found = FamilyHouses.find(g, w, "");
            subject = placeholder(found != null ? FamilyHouses.labelOf(g, found) : FamilyHouses.label(w, "", ""), "member", w);
        }
        return new FamilyAccount.Fact(subject, f.relation(), f.object(), f.date(), f.quote(), d);
    }

    /** A fact without the reader's marks that only {@link #described} reads. */
    static FamilyAccount.Fact unmarked(FamilyAccount.Fact f) {
        if (!f.detail().containsKey(MARKED) && !f.detail().containsKey(NEAR) && !f.detail().containsKey(PICKED)) return f;
        Map<String, String> d = new LinkedHashMap<>(f.detail());
        d.remove(MARKED); d.remove(NEAR); d.remove(PICKED);
        return new FamilyAccount.Fact(f.subject(), f.relation(), f.object(), f.date(), f.quote(), d);
    }

    // ── step 2: the same passage first ───────────────────────────────────────────────────────────────────────────────

    /**
     * The nearest full name before the quote in the same piece whose family part is the word: a name of this read in Latin letters with the
     * word as one of its words, or in characters beginning with it, or whose {@code family} the read gives as the word. The person the phrase
     * is about ({@code besides}) is never it. "" when there is none. Kept as a candidate, never as the answer.
     */
    static String inDocument(String piece, String quote, String word, List<FamilyAccount.Person> people, List<String> names, String besides) {
        if (piece == null || quote == null || word == null) return "";
        String text = norm(piece);
        int at = text.indexOf(norm(quote).strip());
        if (at < 0 && quote.length() > 12) at = text.indexOf(norm(quote).strip().substring(0, 12));
        if (at <= 0) return "";
        String before = text.substring(0, at);
        Map<String, String> familyOf = new HashMap<>();
        for (FamilyAccount.Person p : people) if (!p.family().isBlank()) familyOf.put(p.name(), p.family());
        String best = "";
        int bestAt = -1;
        for (String n : names) {
            if (n == null || n.isBlank() || n.equals(besides) || !full(n) || FamilyQuestions.placeholder(n)) continue;
            boolean fits = familyOf.containsKey(n) ? FamilyForms.sameForm(familyOf.get(n), word) : hasFamilyWord(n, word);
            if (!fits) continue;
            int where = before.lastIndexOf(norm(n));
            if (where > bestAt) { bestAt = where; best = n; }
        }
        return best;
    }

    private static boolean hasFamilyWord(String name, String word) {
        String sc = FamilyForms.script(name);
        if (!sc.equals(FamilyForms.script(word))) return false;
        if (sc.equals("latin")) { for (String w : name.replace(",", " ").split("\\s+")) if (FamilyForms.sameForm(w, word)) return true; return false; }
        if (sc.equals("han")) return FamilyForms.hanKey(name).startsWith(FamilyForms.hanKey(word)) && FamilyForms.hanKey(name).length() > FamilyForms.hanKey(word).length();
        return FamilyForms.kanaKey(name).startsWith(FamilyForms.kanaKey(word)) && FamilyForms.kanaKey(name).length() > FamilyForms.kanaKey(word).length();
    }

    // ── steps 3 to 6: who it can be, and the link ────────────────────────────────────────────────────────────────────

    /**
     * After a read is filed: each described person it made ({@code placeholders}, as written) is linked to the one person it can be, when
     * the rule allows ({@link #candidates}, and a second fact that agrees). One that stays is linked to the family of that name by a
     * membership (how unstated, the same words and sources). The sentences for the read's summary: each link made, with the command that
     * takes it back.
     */
    public static List<String> afterFiling(LibraryStore store, List<String> placeholders) throws IOException {
        List<String> out = new ArrayList<>();
        if (placeholders == null || placeholders.isEmpty()) return out;
        Graph g = FamilyPeople.view(store);
        for (String p : new LinkedHashSet<>(placeholders)) {
            if (!isMention(p)) continue;
            String id = g.nodeIdOf(p);
            if (!id.equals(Vocabulary.norm(p)) || g.node(id) == null) continue;   // linked already
            String said = link(store, g, id);
            if (said != null) { out.add(said); g = FamilyPeople.view(store); continue; }
            if (toFamily(store, g, id)) g = FamilyPeople.view(store);
        }
        return out;
    }

    /**
     * After every whole read and after a tree file or Geni: every described person still unlinked is tried again, because new evidence can
     * settle an older one. The sentences for the summary, as {@link #afterFiling} gives them.
     */
    public static List<String> again(LibraryStore store) throws IOException {
        List<String> out = new ArrayList<>();
        Graph g = FamilyPeople.view(store);
        List<String> waiting = new ArrayList<>();
        for (Graph.Node n : g.nodes()) if ("person".equals(n.kind()) && isMention(n.label()) && n.id().equals(Vocabulary.norm(n.label()))) waiting.add(n.id());
        for (String id : waiting) {
            if (g.node(id) == null) continue;
            String said = link(store, g, id);
            if (said != null) { out.add(said); g = FamilyPeople.view(store); }
        }
        return out;
    }

    /**
     * The year the phrase is about: a parent bore the name when the person beside them was born; a husband or a wife when the relation's claim
     * is dated, else at that birth; somebody of a family, written beside no person, when their own claims date them. Null when nothing says.
     * A child, a brother or a sister is looked for at their own birth ({@link #ownBirth}).
     */
    static Integer year(FamilyNameHistory.Index idx, Graph g, String id, String anchor, String role) {
        FamilyDate born = idx.born(anchor);
        Integer gen = generation(role);
        if (gen != null && gen >= 0 && !role.matches("husband|wife|husband or wife") && born != null) return born.year();
        for (Graph.Edge e : g.edges()) {
            if (FamilyKin.gone(e) || !(e.from().equals(id) && e.to().equals(anchor) || e.to().equals(id) && e.from().equals(anchor))) continue;
            Finding f = idx.finding(e.findingId());
            FamilyDate d = f == null ? null : FamilyChecks.claimDate(f);
            if (d != null) return d.year();
        }
        if (born != null) return born.year();
        FamilyDate own = idx.born(id) != null ? idx.born(id) : idx.died(id);
        return own == null ? null : own.year();
    }

    /** Whether the people a described person of this role can be are looked for by the name they bore at their own birth: a child, a brother, a sister. */
    static boolean ownBirth(String role) {
        Integer gen = generation(role);
        return gen != null && (gen == -1 || role.matches("brother|sister|brother or sister"));
    }

    /**
     * Whether a person bore a family name at their birth: the name they carried in their birth year has that family part, or, where the
     * claims give no birth year or no name for it, the one name they are known by has it, unless that name dates from after the birth (a
     * name taken on entering a family), as {@link FamilyNameHistory.Index#bearers} holds it.
     */
    static boolean boreAtBirth(FamilyNameHistory.Index idx, String id, String family) {
        FamilyDate b = idx.born(id);
        FamilyNameHistory.Name then = b == null ? null : idx.at(id, b.year());
        if (then == null) {
            List<FamilyNameHistory.Name> replacing = idx.names(id).stream().filter(FamilyNameHistory.Name::replaces).toList();
            if (replacing.size() != 1) return false;
            then = replacing.get(0);
            if (then.from() != null && (b == null || then.from().year() > b.year())) return false;
        }
        if (then.hasFamily(family)) return true;
        String[] p = then.family().isBlank() ? idx.parts(id, then.written()) : new String[]{"", ""};
        return !p[0].isBlank() && FamilyForms.sameForm(p[0], family);
    }

    /**
     * The people a described person can be, by node id, as the rule finds them: people who bore that family name at the date the phrase is
     * about ({@link FamilyNameHistory.Index#bearers}), or for a child, a brother or a sister at their own birth ({@link #ownBirth}), and the head
     * of a family of that name then, and the person the model took the party for and the nearest earlier full name of the same passage; each kept
     * only when the relation in the phrase fits their years. The person the phrase is about is never among them, nor their brothers and sisters
     * unless the phrase makes them one, nor anybody the family said is another person, nor anybody the family took a link back from. The model's
     * reading and the passage's name come first, then the others by name.
     */
    public static List<String> candidates(FamilyNameHistory.Index index, Graph g, String placeholder) {
        String[] p = parts(placeholder);
        if (p == null || index == null || g == null) return List.of();
        String id = g.nodeIdOf(placeholder), anchor = g.nodeIdOf(p[0]), role = p[1], w = p[2];
        Integer year = year(index, g, id, anchor, role);
        Set<String> found = new LinkedHashSet<>();
        List<String> first = new ArrayList<>();
        for (String near : nears(index, g, id)) { String n = g.nodeIdOf(near); if (g.node(n) != null && "person".equals(g.node(n).kind())) { found.add(n); first.add(n); } }
        List<String> ws = forms(index, g, w);
        for (String form : ws) {
            if (ownBirth(role)) { for (Graph.Node n : g.nodes()) if ("person".equals(n.kind()) && boreAtBirth(index, n.id(), form)) found.add(n.id()); }
            else if (year != null) found.addAll(index.bearers(form, year));
            if (year != null) for (String fam : FamilyHouses.named(g, form)) for (FamilyHouses.Membership m : FamilyHouses.heads(g, fam)) if (m.holds(year)) found.add(m.person());
        }
        Set<String> out = new LinkedHashSet<>();
        Set<String> apart = new LinkedHashSet<>(), undone = new LinkedHashSet<>();
        try { if (g.store() != null) { apart = Graph.differentPairs(g.store()); undone = takenBack(g.store(), id); } } catch (IOException e) { }
        Set<String> kin = role.matches("brother|sister|brother or sister") ? Set.of() : siblingsOf(g, anchor);
        for (String c : found) {
            if (c.equals(id) || c.equals(anchor) || kin.contains(c) || apart.contains(Graph.pair(c, id)) || undone.contains(c)) continue;
            Graph.Node n = g.node(c);
            if (n == null || !"person".equals(n.kind()) || FamilyQuestions.placeholder(n.label())) continue;
            // an entry written by the family name alone ("Endo", from an older read) is not somebody who bore it: who that is, is asked
            if (!full(n.label()) && ws.stream().anyMatch(x -> FamilyForms.sameForm(x, n.label()))) continue;
            if (!fits(index, g, c, anchor, role)) continue;
            out.add(c);
        }
        List<String> sorted = new ArrayList<>(out);
        sorted.sort(Comparator.comparing((String c) -> first.contains(c) ? first.indexOf(c) : first.size()).thenComparing(c -> g.node(c).label()));
        return sorted;
    }

    /**
     * The people a link of this described person was taken back from by the family ({@code graph unmerge}, an answer): the library does not make
     * that link again. A reset's take-back is a fresh start, not the family's word, and the next read may link it again.
     */
    static Set<String> takenBack(LibraryStore store, String id) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        Path merges = Graph.mergesFile(store);
        if (!Files.exists(merges)) return out;
        for (String line : Files.readAllLines(merges, StandardCharsets.UTF_8)) {
            String[] c = line.split("\t");
            if (c.length >= 3 && c[0].equals("-") && c[1].equals(id) && !(c.length >= 4 && byTheLibrary(c[3]))) out.add(c[2]);
        }
        return out;
    }

    /** Whether a merges.tsv line was written by the library's own work (a reset, a tidy, a tree import, Geni) rather than by a person. */
    private static boolean byTheLibrary(String by) {
        String b = by == null ? "" : by.strip();
        return b.startsWith("genealogy ") || b.equals("geni") || b.equals("gedcom-import") || b.equals("family-account");
    }

    /** The word as written, and the family parts in another script that a source reads as it: 遠藤 when a name of the library with the family part 遠藤 has the romaji form Endo. */
    static List<String> forms(FamilyNameHistory.Index idx, Graph g, String word) {
        Set<String> out = new LinkedHashSet<>(List.of(word));
        String sc = FamilyForms.script(word);
        for (Graph.Node n : g.nodes()) {
            if (!"person".equals(n.kind())) continue;
            for (FamilyNameHistory.Name x : idx.names(n.id()))
                if (!x.implicit() && !x.family().isBlank() && !FamilyForms.script(x.family()).equals(sc) && x.hasFamily(word)) out.add(x.family());
        }
        return new ArrayList<>(out);
    }

    /** The person the model took a described person for ({@link #PICKED}) and the nearest earlier full names of the passage ({@link #NEAR}), as its claims keep them. */
    private static List<String> nears(FamilyNameHistory.Index idx, Graph g, String id) {
        List<String> out = new ArrayList<>();
        for (Graph.Edge e : g.edges()) {
            if (FamilyKin.gone(e) || !(e.from().equals(id) || e.to().equals(id))) continue;
            Finding f = idx.finding(e.findingId());
            for (String n : List.of(FamilyDetail.get(f, PICKED), FamilyDetail.get(f, NEAR))) if (!n.isBlank() && !out.contains(n)) out.add(n);
        }
        return out;
    }

    /** A person's brothers and sisters: those who share a birth parent with them, and those a claim says are. */
    private static Set<String> siblingsOf(Graph g, String id) {
        Set<String> out = new LinkedHashSet<>();
        Map<String, List<FamilyKin.Link>> parents = FamilyKin.parents(g);
        Set<String> mine = new LinkedHashSet<>();
        for (FamilyKin.Link l : parents.getOrDefault(id, List.of())) mine.add(l.other());
        parents.forEach((child, links) -> { if (!child.equals(id)) for (FamilyKin.Link l : links) if (mine.contains(l.other())) out.add(child); });
        for (Graph.Edge e : g.edges()) if (!FamilyKin.gone(e) && e.predicate().equals("sibling-of")) { if (e.from().equals(id)) out.add(e.to()); if (e.to().equals(id)) out.add(e.from()); }
        return out;
    }

    /**
     * Whether a candidate's years fit the relation in the phrase: a parent born at least {@link FamilyChecks#YOUNGEST_PARENT} years before the
     * person beside them and alive at the birth (a father up to a year before it), a child at least as many years after, a husband, a wife, a
     * brother or a sister of the same generation; years nobody gives fit.
     */
    static boolean fits(FamilyNameHistory.Index idx, Graph g, String candidate, String anchor, String role) {
        Integer gen = generation(role);
        if (gen == null) return true;
        FamilyDate cb = idx.born(candidate), ab = idx.born(anchor), cd = idx.died(candidate);
        Map<String, Map<String, List<String>>> sexes = FamilyKin.sexes(g);
        String sex = FamilyKin.sexOf(sexes, candidate);
        if ((role.equals("father") || role.equals("son") || role.equals("husband") || role.equals("brother")) && sex.equals("female")) return false;
        if ((role.equals("mother") || role.equals("daughter") || role.equals("wife") || role.equals("sister")) && sex.equals("male")) return false;
        if (ab == null) return true;
        if (gen == 1) {
            if (cb != null && cb.earliest() > ab.latest() - FamilyChecks.YOUNGEST_PARENT) return false;
            if (cd != null && cd.latest() < ab.earliest() - (sex.equals("female") ? 0 : 1)) return false;
        } else if (gen == -1) {
            if (cb != null && cb.latest() < ab.earliest() + FamilyChecks.YOUNGEST_PARENT) return false;
        } else if (cb != null && Math.abs(cb.year() - ab.year()) > FamilyAccount.SPOUSE_SPAN) return false;
        return true;
    }

    /**
     * Step 5: link a described person to the one candidate with a second fact that agrees and nothing that differs ({@link FamilySame#compare}),
     * by a merge the library makes ({@link #BY}) with the reason: the date, how many bore the name, what agrees and the claims it rests on.
     * The sentence for the summary, or null when no one candidate has such a fact.
     */
    static String link(LibraryStore store, Graph g, String id) throws IOException {
        Graph.Node node = g.node(id);
        if (node == null) return null;
        String placeholder = node.label();
        String[] p = parts(placeholder);
        if (p == null) return null;
        FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
        List<String> cands = candidates(idx, g, placeholder);
        if (cands.isEmpty()) return null;
        Map<String, Finding> findings = new HashMap<>();
        for (Finding f : FamilyPeople.findings(g)) findings.put(f.id(), f);
        // the words that wrote the described person down: a claim from the same words, read again or by an older build, is no second fact
        Set<String> own = new HashSet<>();
        for (Graph.Edge e : g.edges()) if (!FamilyKin.gone(e) && (e.from().equals(id) || e.to().equals(id)) && findings.get(e.findingId()) != null) own.add(quoteOf(findings.get(e.findingId())));
        own.remove("");
        String chosen = null;
        List<FamilySame.Point> why = null;
        for (String c : cands) {
            FamilySame.Comparison cmp = FamilySame.compare(g, findings, id, c);
            List<FamilySame.Point> agree = cmp.agree().stream().filter(pt -> pt.findings().stream().anyMatch(x -> findings.get(x) != null && !own.contains(quoteOf(findings.get(x))))).toList();
            if (cmp.twoPeople() || agree.isEmpty() || !cmp.differ().isEmpty()) continue;
            if (chosen != null) return null;   // two with a fact that agrees: the family says which
            chosen = c; why = agree;
        }
        if (chosen == null) return null;
        String to = g.node(chosen).label();
        String anchor = g.node(g.nodeIdOf(p[0])) == null ? p[0] : g.node(g.nodeIdOf(p[0])).label();
        // a child, a brother or a sister bore the name at their own birth; the others at the date the phrase is about
        Integer year = ownBirth(p[1]) ? (idx.born(chosen) == null ? null : idx.born(chosen).year()) : year(idx, g, id, g.nodeIdOf(p[0]), p[1]);
        Set<String> words = new LinkedHashSet<>();
        for (Graph.Edge e : g.edges()) if (!FamilyKin.gone(e) && (e.from().equals(id) || e.to().equals(id)) && FamilyAccount.personToPerson(e.predicate())) words.add(code(e.findingId()));
        List<String> agreeing = new ArrayList<>();
        for (FamilySame.Point pt : why) agreeing.add(pt.text() + " (" + String.join(", ", pt.findings().stream().map(FamilyMentions::code).distinct().toList()) + ")");
        String reason = to + " bore the family name " + p[2] + (year == null ? "" : " in " + year) + " and fits as " + anchor + "'s " + p[1]
                + (cands.size() == 1 ? ", and is the only person in the library who does. A second fact agrees: "
                        : ". Of the " + cands.size() + " people in the library who do, only " + to + " has a second fact that agrees: ")
                + String.join("; ", agreeing) + (words.isEmpty() ? "" : ". The words are in " + String.join(", ", words));
        Graph.merge(store, placeholder, to, BY, reason, new GenealogyProfile());
        String said = "The library linked \"" + placeholder + "\" to " + to + ". " + reason + ". If that is wrong, this command takes the link back: researchzosho graph unmerge \"" + placeholder + "\"";
        String family = inCharacters(store, g, idx, id, chosen, p[2]);
        return family == null ? said : said + "\n" + family;
    }

    /**
     * A family made for described people written in Latin letters ("Endo family", for "Endo's son") is the family of the person one of them
     * was linked to, written as that person's family name is written, when a source reads that family name as the word (遠藤, which a tree
     * file romanises Endo): the family made for the mention joins the 遠藤 family, found or made, by a merge the library makes, with the
     * reason. Only a family nobody but described people belong to, and only when one family of that name, or none, exists. The sentence for
     * the summary, or null.
     */
    static String inCharacters(LibraryStore store, Graph g, FamilyNameHistory.Index idx, String id, String person, String word) throws IOException {
        if (!FamilyForms.script(word).equals("latin")) return null;
        String fam = idx.parts(person, g.node(person).label())[0];
        if (fam.isBlank() || FamilyForms.script(fam).equals("latin")) return null;
        // the source that reads the family name as the word: a name of the library with that family part and a form in Latin letters with the word
        String reading = null;
        for (Graph.Node n : g.nodes()) {
            if (!"person".equals(n.kind()) || reading != null) continue;
            for (FamilyNameHistory.Name x : idx.names(n.id())) if (!x.implicit() && FamilyForms.sameForm(x.family(), fam) && x.hasFamily(word) && !x.claims().isEmpty()) { reading = x.claims().get(0); break; }
        }
        if (reading == null) return null;
        List<String> there = FamilyHouses.named(g, fam);
        if (there.size() > 1) return null;
        for (Graph.Edge e : g.edges()) {
            if (FamilyKin.gone(e) || !e.from().equals(id) || !e.predicate().equals(FamilyHouses.MEMBER) || !FamilyHouses.isFamily(g, e.to())) continue;
            String made = FamilyHouses.labelOf(g, e.to());
            if (!FamilyForms.sameForm(FamilyHouses.nameOf(made), word)) continue;
            List<String> claims = new ArrayList<>();
            boolean onlyDescribed = true;
            for (Graph.Edge m : g.edges()) {
                if (FamilyKin.gone(m) || !m.to().equals(e.to()) || !m.predicate().equals(FamilyHouses.MEMBER)) continue;
                Graph.Node who = g.node(m.from());
                if (who == null || !isMention(who.label())) onlyDescribed = false;
                claims.add(code(m.findingId()));
            }
            if (!onlyDescribed) continue;
            // two families whose seats differ, or a family and its branch, are two families: which one is meant is the family's to answer
            if (there.size() == 1 && FamilyHouses.toldApart(g, e.to(), there.get(0))) continue;
            String into = there.size() == 1 ? FamilyHouses.labelOf(g, there.get(0)) : FamilyHouses.family(store, g, fam, "", "", List.of());
            if (into == null) return null;
            String reason = "The " + made + " was made for \"" + g.node(id).label() + "\", who is " + g.node(person).label() + ", whose family name " + fam + " the sources read as " + word
                    + " (" + code(reading) + "). The words are in " + String.join(", ", claims.stream().distinct().toList());
            Graph.merge(store, made, into, BY, reason, new GenealogyProfile());
            return "The library wrote the " + made + " as the " + into + ". " + reason + ". If that is wrong, this command takes it back: researchzosho graph unmerge \"" + made + "\"";
        }
        return null;
    }

    /** A claim's short code, F-0012, as a reason cites it. */
    static String code(String id) { return id == null ? "" : id.replaceFirst("^(F-\\d+).*", "$1"); }

    private static final Pattern SAYS = Pattern.compile("(?s)\\n\\nThe account says: \"(.*?)\"\\n");

    /** The words a claim of the family's account was read from, flattened; "" for a claim without them (a tree file's). */
    private static String quoteOf(Finding f) {
        Matcher m = SAYS.matcher(f.body());
        return m.find() ? FamilyAccount.flat(m.group(1)) : "";
    }

    /**
     * Step 6: a described person nobody could be linked to belongs to the family of its name: the family its own name is written beside
     * ({@code Endo family's member (written only as Endo)}), else the one family of that name, or a new one when there is none, by a
     * membership whose way in is not known, from the words and the sources of the claim that wrote it down: a relation, or a fact of that
     * person's own life ("Endo died in 1921"). Two families of the name: none, because a family name alone never picks one of two families.
     * Whether a claim was written.
     */
    static boolean toFamily(LibraryStore store, Graph g, String id) throws IOException {
        Graph.Node node = g.node(id);
        String[] p = node == null ? null : parts(node.label());
        if (p == null) return false;
        String w = p[2];
        for (Graph.Edge e : g.edges()) if (!FamilyKin.gone(e) && e.from().equals(id) && e.predicate().equals(FamilyHouses.MEMBER)) return false;
        String anchor = g.nodeIdOf(p[0]);
        List<String> named = FamilyHouses.isFamily(g, anchor) ? List.of(anchor) : FamilyHouses.named(g, w);
        if (named.size() > 1) return false;
        Finding said = null;
        for (int pass = 0; pass < 2 && said == null; pass++)
            for (Graph.Edge e : g.edges()) {
                if (FamilyKin.gone(e) || !(e.from().equals(id) || e.to().equals(id))) continue;
                // a relation that wrote the person down first; else a fact of their own life
                if (pass == 0 && !(FamilyAccount.personToPerson(e.predicate()) || FamilyAccount.associate(e.predicate()))) continue;
                Finding f = store.finding(e.findingId());
                if (f != null) { said = f; break; }
            }
        if (said == null) return false;
        String label = named.size() == 1 ? FamilyHouses.labelOf(g, named.get(0)) : FamilyHouses.family(store, g, w, "", "", List.of());
        if (label == null) return false;
        Map<String, String> detail = FamilyHouses.detail("unstated", "", "", "", "", "", "");
        Matcher m = SAYS.matcher(said.body());
        String quote = m.find() ? m.group(1) : "";
        String claim = FamilyHouses.sentence(node.label(), label, detail) + ".";
        String fid = store.nextFindingId(node.label() + " member of " + label);
        Finding f = new Finding(fid, Acquisitions.compress(claim, 80), List.of(), Finding.State.draft, Finding.ClaimType.extraction, Finding.Confidence.low, "family-account",
                Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "", said.sources(), List.of(), null,
                claim + (quote.isBlank() ? "\n" : "\n\nThe account says: \"" + quote + "\"\n"), new Finding.Triple(node.label(), FamilyHouses.MEMBER, label), List.of(FamilyDetail.note(detail, "family-account")));
        store.write(f);
        return true;
    }
}
