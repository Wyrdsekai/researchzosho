package org.researchzosho.librarian;

import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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
 * A person's names over a life. A person carries several NAMES: one at birth, one taken at marriage, on adoption, on entering a family as
 * 婿養子, as heir, by a legal change, a pen or religious name. Each name is a claim of its own ({@code <person> | has-name | name: <the
 * name>}) with its sources, and a {@link FamilyDetail} note that reads it: its family and given parts as written, its kind, from and to,
 * the claim of the event that caused it, and its written forms. The written forms of ONE name (森田健二, もりた けんじ, Morita Kenji) are
 * forms of that name, never names of their own.
 *
 * <p>Everything here is worked out from the claims each time, never stored: which name a person carried in a year, which name is the
 * latest, the heading a page shows ({@code 森田健二 (born 遠藤)}), the years each name was in use by the records written under it, and who
 * bore a family name at a date. Where the evidence does not say, the answer is "not known", never a guess. A library from before names
 * were claims keeps working: a person's label and other names are read as forms of an implicit name, and another name with a different
 * family part as an implicit name of a kind not known yet. Nothing is rewritten.
 */
public final class FamilyNameHistory {

    private FamilyNameHistory() { }

    /** The relation of a name claim. */
    public static final String PREDICATE = "has-name";
    /** A name claim's object is the name after this, so that the name is a value of its own and never the person's node. */
    public static final String VALUE = "name: ";

    /** One written form of a name, with its BCP-47 tag when known (ja-Hani, ja-Hira, ja-Latn, en), "" otherwise. */
    public record Form(String text, String lang) { }

    /** The kinds of a name, each with the words a person reads for it. Values, not relations: a later version may add one. */
    public static final Map<String, String> KINDS = kinds();

    private static Map<String, String> kinds() {
        Map<String, String> k = new LinkedHashMap<>();
        k.put("birth", "born with it");
        k.put("marriage", "taken at marriage");
        k.put("adoptive", "on adoption");
        k.put("mukoyoshi", "on entering the family as 婿養子 (adopted and married)");
        k.put("nyufu", "on entering by 入夫 marriage");
        k.put("succession", "on succeeding as head, or a hereditary name");
        k.put("legal", "by a legal or court change, or a will's name clause");
        k.put("taken-back", "taken back after a divorce or an ended adoption");
        k.put("imposed", "imposed by law");
        k.put("farm", "from the farm or house lived at");
        k.put("immigrant", "taken on immigration");
        k.put("religious", "a religious or posthumous name");
        k.put("art", "an art, pen or professional name");
        k.put("aka", "also known as");
        k.put("unknown", "for a reason not known yet");
        k.put("hereditary", "the hereditary head name of a family");
        k.put("earlier", "the earlier name; how it changed is not known");
        k.put("later", "the later name; how it changed is not known");
        return Collections.unmodifiableMap(k);
    }

    /** Names carried beside the others, which end none of them. */
    static final Set<String> ALONGSIDE = Set.of("religious", "art", "aka", "hereditary");

    /** Whether a name of this kind takes the place of the name before it. */
    public static boolean replaces(String kind) { return !ALONGSIDE.contains(kind == null || kind.isBlank() ? "unknown" : kind); }

    /** A kind as the history keeps it: one of {@link #KINDS}, "unknown" for anything else. */
    public static String kind(String k) {
        String w = k == null ? "" : k.strip().toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
        if (w.equals("婿養子") || w.equals("muko-yoshi") || w.equals("mukoyōshi")) return "mukoyoshi";
        if (w.equals("入夫") || w.equals("nyūfu")) return "nyufu";
        if (w.equals("married") || w.equals("maiden")) return w.equals("married") ? "marriage" : "birth";
        if (w.equals("adoption") || w.equals("adopted")) return "adoptive";
        if (w.equals("professional") || w.equals("pen")) return "art";
        return KINDS.containsKey(w) ? w : "unknown";
    }

    /** The words for how a name came, in a sentence about it: "at birth", "on adoption". "" for a name whose kind is not known. */
    static String phrase(String kind) {
        return switch (kind(kind)) {
            case "birth" -> "at birth";
            case "marriage" -> "at marriage";
            case "adoptive" -> "on adoption";
            case "mukoyoshi" -> "on entering the family as 婿養子 (adopted and married)";
            case "nyufu" -> "on entering the family by 入夫 marriage";
            case "succession" -> "on succeeding as head of the family";
            case "legal" -> "by a legal change";
            case "taken-back" -> "on taking it back";
            case "imposed" -> "by a law that imposed it";
            case "farm" -> "after the farm or house lived at";
            case "immigrant" -> "on immigration";
            case "religious" -> "as a religious or posthumous name";
            case "art" -> "as an art, pen or professional name";
            case "earlier" -> "as the earlier name";
            case "later" -> "as the later name";
            default -> "";
        };
    }

    /**
     * The first line of a name claim, the substance a person approves: whose name, the name, how it came, and the date in the last
     * brackets, which is where the checks read a claim's date. "森田健二 was named 遠藤健二 at birth (1905)."
     */
    public static String claimSentence(String person, String name, String kind, String dateShown) {
        String k = kind(kind);
        String when = dateShown == null || dateShown.isBlank() ? "" : " (" + dateShown.strip() + ")";
        String said = switch (k) {
            case "aka" -> person + " was also known as " + name;
            case "hereditary" -> person + " had the hereditary head name " + name;
            default -> person + " was named " + name + (phrase(k).isEmpty() ? "" : " " + phrase(k));
        };
        return said + when + ".";
    }

    /**
     * A name claim's reading ({@link FamilyDetail}): the parts as written (never split by position), the kind, the source's own words for
     * how, from and to (a date as written, or "event"), the claim of the event that caused it, and the forms as {@code text@lang}.
     */
    public static Map<String, String> detail(String family, String given, String kind, String from, String to, String event, List<Form> forms, String said, String lang) {
        // a form in Latin letters is romaji (ja-Latn) only beside a form of the name in Japanese characters or kana; an English name is und-Latn
        boolean japanese = FamilyForms.japanese(nz(family)) || FamilyForms.japanese(nz(given));
        if (forms != null) for (Form f : forms) japanese |= FamilyForms.japanese(f.text());
        List<Form> tagged = new ArrayList<>();
        if (forms != null) for (Form f : forms) tagged.add(new Form(f.text(), latinTag(f.text(), f.lang(), japanese)));
        Map<String, String> d = new LinkedHashMap<>();
        d.put("family", nz(family));
        d.put("given", nz(given));
        d.put("kind", kind(kind));
        d.put("from", nz(from));
        d.put("to", nz(to));
        d.put("event", nz(event));
        d.put("forms", formsText(tagged));
        d.put("said", nz(said));
        if (lang != null && !lang.isBlank()) d.put("lang", isLatinTag(lang.strip()) ? (japanese ? "ja-Latn" : "und-Latn") : lang.strip());
        return d;
    }

    /** A tag the program itself gives a form in Latin letters ("", und-Latn, ja-Latn) is given again by the name's other forms; any other tag is the source's and stays. */
    private static String latinTag(String text, String lang, boolean japanese) {
        if (!FamilyForms.script(text).equals("latin")) return lang == null ? "" : lang;
        return lang == null || isLatinTag(lang.strip()) ? FamilyForms.lang(text, japanese) : lang;
    }

    private static boolean isLatinTag(String lang) { return lang.isEmpty() || lang.equals("und-Latn") || lang.equals("ja-Latn"); }

    static String formsText(List<Form> forms) {
        List<String> out = new ArrayList<>();
        if (forms != null) for (Form f : forms) {
            String t = f.text() == null ? "" : f.text().replaceAll("\\s*[,，、]\\s*", " ").replace("@", " ").strip();
            if (!t.isEmpty()) out.add(t + (f.lang() == null || f.lang().isBlank() ? "" : "@" + f.lang().strip()));
        }
        return String.join(",", out);
    }

    static List<Form> formsOf(String text) {
        List<Form> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        for (String part : text.split(",")) {
            String p = part.strip();
            if (p.isEmpty()) continue;
            int at = p.lastIndexOf('@');
            out.add(at > 0 ? new Form(p.substring(0, at).strip(), p.substring(at + 1).strip()) : new Form(p, FamilyForms.lang(p)));
        }
        return out;
    }

    private static String nz(String s) { return s == null ? "" : s.strip(); }

    /** The name a name claim gives, as written: its object without {@link #VALUE} in front ("name: 遠藤健二" is 遠藤健二). */
    public static String written(Finding f) { return f == null || f.triple() == null ? "" : bare(f.triple().object()); }

    /** A name claim's object without {@link #VALUE} in front. */
    public static String bare(String object) {
        String o = object == null ? "" : object.strip();
        return o.regionMatches(true, 0, VALUE, 0, VALUE.length()) ? o.substring(VALUE.length()).strip() : o;
    }

    // ── the words that say how a name came ────────────────────────────────────────────────────────────────────────────

    /**
     * The words that say each kind, by default: a kind is kept only when the quote says it in words, and these are where the program looks
     * first. They are defaults behind that rule, not the rule: the reader asks the model's judge about any other wording.
     */
    static final Map<String, Pattern> SAYS = says();

    private static Map<String, Pattern> says() {
        Map<String, Pattern> m = new LinkedHashMap<>();
        m.put("mukoyoshi", Pattern.compile("(?i)婿養子|婿入り|\\bmuko-?y[oō]u?shi\\b|adopted son-in-law"));
        m.put("nyufu", Pattern.compile("(?i)入夫|\\bny[uū]u?fu\\b"));
        m.put("adoptive", Pattern.compile("(?i)養子|養女|\\badopt(?:ed|ion|ive)\\b"));
        m.put("succession", Pattern.compile("(?i)家督|襲名|跡を継|跡継|\\bsucceed(?:ed|s)?\\b|\\bsuccession\\b|\\bas head\\b"));
        m.put("birth", Pattern.compile("(?i)旧姓|生家|\\bnée\\b|\\bnee\\b|\\bborn\\b|\\bmaiden name\\b|\\bbirth name\\b|生まれ"));
        m.put("marriage", Pattern.compile("(?i)\\bmarried name\\b|\\bon (?:her|his) marriage\\b|\\btook (?:her|his|their) (?:(?:husband|wife)'?s )?(?:family )?(?:name|surname)\\b|\\bmarried\\b|結婚|嫁"));
        m.put("legal", Pattern.compile("(?i)改名|改姓|\\bchanged (?:his|her|their) (?:name|surname)\\b|\\bdeed poll\\b|\\bby (?:a )?court\\b|\\bname clause\\b|\\bby (?:royal )?licen[cs]e\\b"));
        m.put("taken-back", Pattern.compile("(?i)復氏|離縁|\\bresumed (?:her|his) (?:maiden |former )?(?:name|surname)\\b|\\btook back\\b|\\breverted to\\b"));
        m.put("religious", Pattern.compile("(?i)戒名|法名|法号|洗礼名|霊名|\\breligious name\\b|\\bposthumous name\\b|\\bname in religion\\b|\\b(?:christian|baptismal|confirmation) name\\b|\\bbapti[sz]ed\\b|\\bchristened\\b"));
        m.put("art", Pattern.compile("(?i)雅号|筆名|芸名|俳号|(?<![一-龥])号|\\bpen name\\b|\\bart name\\b|\\bstage name\\b|\\bpseudonym\\b|\\bnom de plume\\b"));
        m.put("aka", Pattern.compile("(?i)通称|別名|英語名|愛称|\\balso known as\\b|\\bknown as\\b|\\ba\\.?k\\.?a\\.?\\b|\\balias\\b|\\bnicknamed\\b|\\b(?:english|american|western|anglici[sz]ed) name\\b"));
        m.put("imposed", Pattern.compile("(?i)創氏|\\bimposed\\b"));
        m.put("farm", Pattern.compile("(?i)\\bfarm name\\b|屋号"));
        m.put("immigrant", Pattern.compile("(?i)\\bon (?:arrival|immigration)\\b|\\bimmigrat(?:ed|ion)\\b|\\bnaturali[sz]ed\\b"));
        m.put("hereditary", Pattern.compile("(?i)名跡|世襲|\\bhereditary name\\b"));
        return Collections.unmodifiableMap(m);
    }

    /**
     * The words that say a person entered a family or married into one, by default: entered the Morita family, married into the family,
     * 森田家に入った, 森田家に嫁いだ, 森田家の婿となった, 婿に入った. More than a marriage may lie behind them (婿養子, 養女, 入夫), so a name
     * they stand beside is asked about, never worked out as the marriage's. A default list behind that rule, as {@link #SAYS} is.
     */
    static final Pattern ENTERED = Pattern.compile("(?i)\\b(?:entered|enters|entering)\\s+(?:into\\s+)?the\\s+(?:[\\p{L}'’-]+\\s+){0,2}(?:family|household|clan)\\b(?!\\s+(?:business|firm|company|shop|store|trade|farm|home|house|estate))"
            + "|\\b(?:married|marries|marrying|adopted|taken)\\s+into\\s+(?:the\\s+|an?\\s+)?(?:[\\p{L}'’-]+\\s+){0,2}(?:family|household|clan|house)\\b(?!\\s+(?:business|firm|company|shop|store|trade|farm|home|estate))"
            + "|家(?:に|へ)(?:入|嫁|婿)|家の(?:婿|嫁)(?:と|に|として)|婿に(?:入|行)|入り?婿");

    /** Whether the words of a quote say this kind of name by themselves ({@link #SAYS}). "unknown" is said by any quote. */
    public static boolean saysKind(String quote, String kind) {
        String k = kind(kind);
        if (k.equals("unknown")) return true;
        Pattern p = SAYS.get(k);
        return p != null && quote != null && p.matcher(quote).find();
    }

    /**
     * The kind a claim's relation words give a name, for a claim that has no reading of its own, such as a research run's triple: "maiden
     * name" is a name at birth, "married name" one taken at marriage, "pen name" an art name. "unknown" when the words do not say.
     */
    public static String kindFromWords(String predicateAsWritten) {
        String w = Vocabulary.norm(predicateAsWritten).replace('-', ' ');
        if (w.matches(".*\\b(birth name|born as|maiden name|née|nee)\\b.*") || w.contains("旧姓")) return "birth";
        if (w.contains("married name")) return "marriage";
        if (w.matches(".*\\b(pen name|art name|stage name)\\b.*") || w.contains("雅号") || w.contains("筆名") || w.equals("号")) return "art";
        if (w.matches(".*\\b(posthumous name|religious name)\\b.*") || w.contains("戒名") || w.contains("法名")) return "religious";
        if (w.matches(".*\\b(also known as|known as)\\b.*") || w.contains("通称") || w.contains("別名")) return "aka";
        return "unknown";
    }

    // ── what a name's own words say of it ─────────────────────────────────────────────────────────────────────────────

    // the words that, written right before a name, say how it came; each ends where the name begins. Defaults behind the rule that a text's
    // own words for a name, tied to that name, give its kind
    private static final String IS = "\\s*(?:is|was|being)?\\s*[:：,]?\\s*[“\"'‘「『]?";
    private static final List<Map.Entry<String, Pattern>> SAYS_BEFORE = List.of(
            Map.entry("religious", Pattern.compile("(?i)(?:\\b(?:christian|baptismal|confirmation|religious)\\s+name" + IS + "|\\bname\\s+in\\s+religion" + IS
                    + "|\\b(?:bapti[sz]ed|christened)\\s+(?:as\\s+)?[“\"'‘]?|(?:洗礼名|霊名|戒名|法名)[はがをも]?[:：]?\\s*[「『]?)$")),
            Map.entry("birth", Pattern.compile("(?i)(?:\\bborn\\b[^.;!?。]{0,80}?\\bas\\s+|\\bborn\\s+|\\bn[ée]e\\s+|\\b(?:maiden|birth)\\s+name" + IS + "|旧姓[はがを]?[:：]?\\s*[「『（(]?)$")),
            Map.entry("adoptive", Pattern.compile("(?i)\\badopted\\b[^.;!?。]{0,80}?\\b(?:re)?named\\s+[“\"'‘]?$")),
            Map.entry("aka", Pattern.compile("(?i)(?:(?<!\\b(?:later|then|afterwards|thereafter|subsequently|since|now|formerly|previously|originally)\\s)\\b(?:also\\s+)?known\\s+as\\s+[“\"'‘]?|\\ba\\.?k\\.?a\\.?\\s+|\\balias\\s+|\\bnicknamed\\s+[“\"'‘]?|\\b(?:his|her|their)\\s+(?:english|american|western|anglici[sz]ed)\\s+name" + IS
                    + "|(?:通称|英語名|愛称)[はがを]?[:：]?\\s*[「『]?)$")),
            Map.entry("marriage", Pattern.compile("(?i)(?:\\bmarried\\s+name" + IS + "|\\btook\\s+(?:her|his)\\s+(?:husband|wife)['’]s\\s+(?:family\\s+)?(?:name|surname)\\b[^.;!?。]{0,60}?\\bbecame\\s+)$")));
    // the same written right after a name in Japanese: パウロという洗礼名, 養子となり健一と改名
    private static final List<Map.Entry<String, Pattern>> SAYS_AFTER = List.of(
            Map.entry("religious", Pattern.compile("^[」』]?\\s*(?:という|との|の)?(?:洗礼名|霊名|戒名|法名)")),
            Map.entry("adoptive", Pattern.compile("^[」』]?と(?:改名|名乗|称|名を改)")));
    private static final Pattern ADOPTED_BEFORE_JA = Pattern.compile("養子(?:となり|となって|に入り|となる|となった)[^。]{0,20}$");

    /**
     * The kind a quote's own words give one name, and those words: {kind, words}; null when they say none of it. The words must be tied to
     * the name, written right before or after it: "my Christian name is Paul", "baptised Mary", パウロという洗礼名 (a religious name); "born in
     * Leeds as Ruth Ellis", "née Ellis", 旧姓 (the name at birth); "adopted by the lord and named Kenji", 養子となり健二と改名 (on adoption); "known
     * as Helen", "her English name, Helen", 通称 (another name carried beside the first); "her married name, Hart", "took her husband's name and
     * became Ruth Hart" (at marriage). A Western given name in brackets beside a Japanese one ("Haru (Helen) Morita", "Morita, Haru (Helen;
     * wife)") is a name carried beside the first too. "Later known as" says a later name, not one beside the first. {@code given}: the given
     * part of the name, looked for only where the quote does not write the whole name, and only where it stands alone: the given part of
     * another name ("born Ruth Hale" for Ruth Ellis) says nothing of this one.
     */
    static String[] kindInWords(String quote, String name, String given) {
        if (quote == null || quote.isBlank() || name == null || name.isBlank()) return null;
        String q = quote.replaceAll("\\s+", " ");
        List<String> looked = new ArrayList<>(List.of(name.strip()));
        String bare = FamilyNames.comparable(name.strip());
        if (!looked.contains(bare)) looked.add(bare);
        boolean whole = looked.stream().anyMatch(w -> !where(q, w).isEmpty());
        if (!whole && given != null && !given.isBlank() && !looked.contains(given.strip())) looked.add(given.strip());
        for (String w : looked) {
            for (int at : where(q, w)) {
                String before = q.substring(0, at), after = q.substring(at + w.length());
                if (!whole && after.matches("(?s)^\\s+\\p{Lu}.*")) continue;   // the given part of a longer name
                for (Map.Entry<String, Pattern> k : SAYS_BEFORE) {
                    Matcher m = k.getValue().matcher(before);
                    // "born Ruth Ellis": a name, written with a capital, right after "born"
                    if (m.find() && !(m.group().matches("(?i)born\\s+") && !Character.isUpperCase(w.codePointAt(0)))) return new String[]{k.getKey(), (m.group() + w).strip()};
                }
                for (Map.Entry<String, Pattern> k : SAYS_AFTER) {
                    Matcher m = k.getValue().matcher(after);
                    if (!m.find()) continue;
                    if (k.getKey().equals("adoptive") && !ADOPTED_BEFORE_JA.matcher(before).find()) continue;
                    return new String[]{k.getKey(), (w + m.group()).strip()};
                }
                if (beside(before, after, w)) return new String[]{"aka", before.replaceFirst("^.*?(\\S+\\s*[(（]\\s*)$", "$1") + w + ")"};
            }
        }
        return null;
    }

    /** Where a quote writes a name: as whole words in Latin letters, in any case; anywhere in characters or kana. */
    private static List<Integer> where(String q, String w) {
        List<Integer> out = new ArrayList<>();
        boolean latin = FamilyForms.script(w).equals("latin");
        Matcher m = Pattern.compile((latin ? "(?i)(?<![\\p{L}])" : "") + Pattern.quote(w) + (latin ? "(?![\\p{L}])" : "")).matcher(q);
        while (m.find()) out.add(m.start());
        return out;
    }

    /**
     * Whether a name in Latin letters stands in brackets right after a Japanese given name, as a Western given name carried beside it: "Haru
     * (Helen) Morita", "Morita, Haru (Helen; wife)". A name in the brackets that can be romanised Japanese (Shōichi) is another Japanese
     * name or a reading, and not this.
     */
    private static boolean beside(String before, String after, String w) {
        if (!FamilyForms.script(w).equals("latin") || !after.matches("(?s)^\\s*[)）;；,，、].*")) return false;
        Matcher m = Pattern.compile("(\\S+)\\s*[(（]\\s*$").matcher(before);
        if (!m.find()) return false;
        String outside = m.group(1).replaceAll("[,，、;；:]+$", "");
        String first = w.strip().split("\\s+")[0];
        boolean japanese = FamilyForms.japanese(outside) || FamilyForms.script(outside).equals("latin") && FamilyForms.romajiWord(outside);
        return japanese && !FamilyForms.romajiWord(first);
    }

    // a name in Latin letters at the start of brackets, one word or two, before the bracket closes or a note in it begins
    private static final Pattern IN_BRACKETS = Pattern.compile("[(（]\\s*(\\p{Lu}[\\p{L}'’-]*(?:\\s+\\p{Lu}[\\p{L}'’-]*)?)\\s*(?=[)）;；,，、])");
    private static final Pattern BEFORE_BRACKETS = Pattern.compile("(\\S+)\\s*[(（]\\s*$");

    /**
     * The Western given names a quote writes in brackets right after a Japanese given name, read as {@link #beside} reads them for the
     * names over a life: "Morita, Noriko (Helen; daughter)" and "Noriko (Helen) Morita" give {Noriko, Helen}. Each {the given name outside the
     * brackets, the name in them}; a name in the brackets that can be romanised Japanese is another Japanese name or a reading, and not one.
     */
    static List<String[]> westernInBrackets(String quote) {
        List<String[]> out = new ArrayList<>();
        if (quote == null || quote.isBlank()) return out;
        String q = quote.replaceAll("\\s+", " ");
        Matcher m = IN_BRACKETS.matcher(q);
        while (m.find()) {
            String w = m.group(1), before = q.substring(0, m.start(1)), after = q.substring(m.end(1));
            if (!beside(before, after, w)) continue;
            Matcher o = BEFORE_BRACKETS.matcher(before);
            if (o.find()) out.add(new String[]{o.group(1).replaceAll("[,，、;；:]+$", ""), w});
        }
        return out;
    }

    // a naming verb and its object before a name: "named the farm", "called our house", "christened it", 農場を…と名付けた
    private static final Pattern NAMES_A_THING = Pattern.compile("(?i)\\b(?:named|called|christened|dubbed|renamed)\\s+(?:it|(?:the|our|my|his|her|their|this|that|a|an)\\s+(\\p{L}+(?:\\s+\\p{L}+){0,2}))\\s*[“\"'‘]?$");
    private static final Pattern NAMES_A_THING_JA = Pattern.compile("([^\\s、。「」『』]{1,8})を[「『]?$");
    private static final Pattern NAMED_JA = Pattern.compile("^[」』]?と(?:名付|名づ|命名)");
    // the words for a person, which a naming verb's object may be: then the name is a person's
    private static final Pattern PERSON_WORDS = Pattern.compile("(?i)(?<![\\p{L}])(?:son|daughter|child|baby|boy|girl|brother|sister|wife|husband|father|mother|grandson|granddaughter|nephew|niece|cousin|heir|friend|servant|man|woman|family)s?(?![\\p{L}])"
            + "|子|息子|娘|長男|次男|長女|次女|孫|甥|姪|妻|夫|彼|嫁|婿");

    /**
     * Whether a name a quote gives is the name of a thing, not of a person: the object of the naming verb is a thing ("I named the farm
     * Willow Farm", "we called it Willow Farm", 農場を「柳農場」と名付けた). An object that is a person ("named his son Tom") says nothing
     * against the name.
     */
    static boolean namesAThing(String quote, String name) {
        if (quote == null || name == null || name.isBlank()) return false;
        String q = quote.replaceAll("\\s+", " ");
        for (int at : where(q, name.strip())) {
            String before = q.substring(0, at), after = q.substring(at + name.strip().length());
            Matcher m = NAMES_A_THING.matcher(before);
            if (m.find() && (m.group(1) == null || !PERSON_WORDS.matcher(m.group(1)).find())) return true;
            Matcher j = NAMES_A_THING_JA.matcher(before);
            if (j.find() && NAMED_JA.matcher(after).find() && !PERSON_WORDS.matcher(j.group(1)).find()) return true;
        }
        return false;
    }

    private static final Pattern MRS = Pattern.compile("(?i)^(?:mrs|mme|madame|frau)\\.?\\s+(\\S+(?:\\s+\\S+)+)$");

    /**
     * The name a woman is written by when she is written by her husband's name, "Mrs. Tom Hale": the name after the title, or null for any
     * other name. A title with a family name alone (Mrs. Hale) writes her own family name.
     */
    static String mrsWith(String name) {
        Matcher m = MRS.matcher(name == null ? "" : name.strip());
        return m.matches() ? m.group(1).strip() : null;
    }

    /**
     * Whether a name is a form of address by a man's name, as the quote writes it: "Mrs. Tom Hale" where the words write the man beside her,
     * "Mr. and Mrs. Tom Hale", "Rev. and Mrs. Tom Hale". It is how a married woman was addressed, not a name of hers.
     */
    static boolean addressedByHisName(String quote, String name) {
        String his = mrsWith(name);
        if (his == null || quote == null) return false;
        return Pattern.compile("(?i)\\b\\p{L}+\\.?\\s+(?:and|&)\\s+(?:mrs|mme|madame|frau)\\.?\\s+" + Pattern.quote(his) + "(?![\\p{L}])").matcher(quote.replaceAll("\\s+", " ")).find();
    }

    /**
     * Whether a quote is an index's cross-reference to a name: "Endō, Kenji. See Morita, Kenji", "Endō Kenji → 森田健二", 遠藤健二 → 森田健二を見よ.
     * It says the two forms are one person's; it says nothing of how the name came.
     */
    static boolean crossReference(String quote, String name) {
        if (quote == null || name == null || name.isBlank()) return false;
        Matcher m = Pattern.compile("(?i)^\\s*[^.;]{1,80}?[.,;:]?\\s+(?:see(?:\\s+also)?|→|⇒)\\s*:?\\s+(.{1,80}?)\\s*[.。]?\\s*$").matcher(quote.replaceAll("\\s+", " "));
        Matcher ja = Pattern.compile("[→⇒]\\s*(.{1,40}?)(?:を見よ|を参照|参照)?\\s*[。.]?\\s*$").matcher(quote.strip());
        String target = m.find() ? m.group(1) : ja.find() ? ja.group(1) : null;
        if (target == null) return false;
        return !Collections.disjoint(FamilyNames.keys(FamilyNames.comparable(target)), FamilyNames.keys(FamilyNames.comparable(name)))
                || FamilyForms.sameForm(FamilyNames.comparable(target).replaceAll("[\\s　]+", ""), FamilyNames.comparable(name).replaceAll("[\\s　]+", ""));
    }

    // the words written right before a name that say it is the earlier one: "X (formerly Y)", "X, formerly Y", "X, formerly known as Y", 旧姓 Y
    private static final Pattern FORMERLY = Pattern.compile("(?i)(?:(?:[(（,、;]|\\s)\\s*(?:formerly|previously|originally)(?:\\s+known\\s+as|\\s+called|\\s+named)?|(?:[(（,、;（]|\\s)?\\s*(?:旧姓|旧名|旧称))\\s*[:：]?\\s*[“\"'‘「『]?\\s*$");

    /**
     * The words before a name that say it is the earlier name, and what stands before them: "Yuri Hale (formerly Yuri Ellis)", "Yuri Hale,
     * formerly Yuri Ellis", "formerly known as", 旧姓. The text before the words ("Yuri Hale"), which names the later name; "" when the words
     * stand at the start; null when the quote does not say so of this name.
     */
    static String formerName(String quote, String name) {
        if (quote == null || quote.isBlank() || name == null || name.isBlank()) return null;
        String q = quote.replaceAll("\\s+", " ");
        for (int at : where(q, name.strip())) {
            Matcher m = FORMERLY.matcher(q.substring(0, at));
            if (m.find()) return q.substring(0, m.start()).strip();
        }
        return null;
    }

    /**
     * The kind of an adoption: its reading's {@code kind} (ordinary, mukoyoshi, heir), else the relation as written (婿養子 is mukoyoshi, 養子,
     * 養女 and "adopted into" are ordinary), else what the quote says, else "unstated".
     */
    public static String adoptionKind(Finding f) {
        String k = FamilyDetail.get(f, "kind");
        if (!k.isBlank()) return k;
        String p = f == null || f.triple() == null ? "" : f.triple().predicate();
        if (p.contains("婿養子")) return "mukoyoshi";
        String body = f == null ? "" : f.body();
        if (SAYS.get("mukoyoshi").matcher(body).find()) return "mukoyoshi";
        if (Pattern.compile("(?i)家督|\\bas (?:the )?heir\\b|出嗣|入嗣|継嗣").matcher(body).find()) return "heir";
        if (p.contains("養子") || p.contains("養女") || Vocabulary.norm(p).contains("adopted")) return "ordinary";
        return "unstated";
    }

    // ── filing a name ─────────────────────────────────────────────────────────────────────────────────────────────────

    /** File one name a read gave ({@link #file(LibraryStore, FamilyAccount.NameRead, List, String, List, Map)} with nothing more). */
    public static Finding file(LibraryStore store, FamilyAccount.NameRead n, List<Finding.Source> sources, String writer, List<Finding.Note> notes) throws IOException {
        return file(store, n, sources, writer, notes, Map.of());
    }

    /**
     * File one name a read gave, as a draft claim with its reading: {@code person | has-name | name: <the name>}. The same name said again
     * by another source is that source added to the claim that has it ({@link Evidence#toldAgain}), not a second claim. {@code more}: keys the
     * reading gets beside the read's own ({@code event}, {@code from=event}, {@code to}). The claim as written, or null when nothing changed.
     */
    public static Finding file(LibraryStore store, FamilyAccount.NameRead n, List<Finding.Source> sources, String writer, List<Finding.Note> notes, Map<String, String> more) throws IOException {
        return file(store, n, sources, writer, notes, more, new ArrayList<>(store.scanFindings().findings()));
    }

    static Finding file(LibraryStore store, FamilyAccount.NameRead n, List<Finding.Source> sources, String writer, List<Finding.Note> notes, Map<String, String> more, List<Finding> shelf) throws IOException {
        if (n == null || n.person() == null || n.person().isBlank() || n.name() == null || n.name().isBlank()) return null;
        String person = n.person().strip();
        // an index's relation note after the name ("Morita, Kenji (father)") is the person's relation to the book's subject, no part of the name
        String[] noted = FamilyNames.indexNote(n.name().strip());
        String name = noted[0], relation = noted[1];
        // no name of the person: a thing's name, a woman written by her husband's name, or how a text addresses them (a title or an initial
        // with the family name alone, saying nothing more)
        if (namesAThing(n.quote(), name) || addressedByHisName(n.quote(), name) || husbandsName(shelf, person, name)) return null;
        if (formOfAddress(name, n.family(), n.given())) return null;
        if (FamilyNames.initials(name) && kind(n.kind()).equals("unknown") && (n.date() == null || n.date().isBlank()) && !abbreviatesOne(name, knownWays(shelf, person))) return null;
        List<Form> forms = new ArrayList<>();
        forms.add(new Form(name, FamilyForms.lang(name)));
        for (String raw : n.forms() == null ? List.<String>of() : n.forms()) {
            String f = raw == null ? "" : FamilyNames.withoutIndexNote(raw.strip());
            if (!f.isBlank() && forms.stream().noneMatch(x -> x.text().equals(f))) forms.add(new Form(f, FamilyForms.lang(f)));
        }
        String kind = kind(n.kind());
        Map<String, String> d = detail(n.family(), n.given(), kind, n.date(), "", "", forms, n.said(), FamilyForms.lang(name));
        forms = formsOf(d.get("forms"));
        if (more != null) more.forEach((k, v) -> { if (k != null && v != null && !v.isBlank()) d.put(k, v.strip()); });
        if (!relation.isBlank()) d.put("relation", relation);
        FamilyDate date = FamilyDate.parse(n.date() == null ? "" : n.date());
        String shown = date != null ? date.shown() : n.date() == null ? "" : n.date().strip();
        String claim = claimSentence(person, name, kind, shown);
        Finding.Triple t = new Finding.Triple(person, PREDICATE, VALUE + name);
        List<Finding.Note> all = new ArrayList<>(notes == null ? List.of() : notes);
        all.add(FamilyDetail.note(d, writer));
        // the same name from another source: that source is kept with the claim that has it
        FamilyAccount.Fact asFact = new FamilyAccount.Fact(person, PREDICATE, VALUE + name, n.date() == null ? "" : n.date(), n.quote() == null ? "" : n.quote());
        List<Finding> same = FamilyAccount.sameFact(shelf, asFact);
        // another source that says more of the name (its kind, its years, the event, its own words for how), says less of it, or says it
        // otherwise is a claim of its own, so the ranking weighs the two and neither source is made to back what only the other says: a
        // register that gives the name alone never backs a book's kind and year. Read again from a source the held claim already has, it
        // stays one claim
        if (!same.isEmpty() && same.stream().noneMatch(x -> hasSource(x, sources))) {
            List<Finding> fits = same.stream().filter(x -> addsNothing(FamilyDetail.of(x), d) && lacksNothing(FamilyDetail.of(x), d)).toList();
            same = fits;
        }
        if (!same.isEmpty()) {
            Finding kept = FamilyAccount.told(same);
            if (kept == null) return null;
            // the forms this source writes the name in, which the claim does not list yet, are kept with it: a reading a letter gives is a form of the name
            Map<String, String> had = FamilyDetail.of(kept);
            List<Form> both = new ArrayList<>(formsOf(had.getOrDefault("forms", "")));
            boolean more2 = false;
            for (Form x : forms) if (both.stream().noneMatch(y -> y.text().equals(x.text()))) { both.add(x); more2 = true; }
            List<Finding.Note> told = new ArrayList<>(notes == null ? List.of() : notes);
            if (more2 && !had.isEmpty()) { Map<String, String> nd = new LinkedHashMap<>(had); nd.put("forms", formsText(both)); told.add(FamilyDetail.note(nd, writer)); }
            Finding now = Evidence.toldAgain(store, kept, sources, writer, n.quote(), told);
            if (now != null) shelf.set(shelf.indexOf(kept), now);
            return now;
        }
        markValue(store, List.of(VALUE + name));
        String id = store.nextFindingId(person + " has name " + name);
        String body = claim + (n.quote() == null || n.quote().isBlank() ? "\n" : "\n\nThe account says: \"" + n.quote().strip() + "\"\n");
        Finding f = new Finding(id, Acquisitions.compress(claim, 80), List.of(), Finding.State.draft, Finding.ClaimType.extraction, Finding.Confidence.low, writer,
                Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "", sources, List.of(), null, body, t, all);
        store.write(f);
        shelf.add(f);
        return f;
    }

    /**
     * Whether a name a read gives is how the text addresses the person, and no name of theirs ({@link FamilyNames#formOfAddress}): a title or
     * an honorific with the family name alone. In characters the read's own parts say it: an honorific with the family part and no given part.
     */
    static boolean formOfAddress(String name, String family, String given) {
        if (FamilyNames.formOfAddress(name)) return true;
        String b = name == null ? "" : name.strip(), u = FamilyNames.untitled(b);
        if (u.equals(b) || !FamilyForms.japanese(u)) return false;
        return (given == null || given.isBlank()) && family != null && !family.isBlank() && FamilyForms.sameForm(u, family.strip());
    }

    /** The ways the library writes a person so far: the name they are filed under, and the names and forms the name claims on the shelf give them. */
    private static List<String> knownWays(List<Finding> shelf, String person) {
        List<String> out = new ArrayList<>(List.of(person));
        for (Finding f : shelf) {
            if (!isNameClaim(f) || !Vocabulary.norm(f.triple().subject()).equals(Vocabulary.norm(person))) continue;
            out.add(written(f));
            for (Form x : formsOf(FamilyDetail.get(f, "forms"))) out.add(x.text());
        }
        return out;
    }

    /**
     * Whether an initial with a family name abbreviates exactly one of the ways a person is written ({@link #initialFits}): "K. Morita" beside
     * "Kenji Morita" alone is that name, written short; beside "Kenji Morita" and "Kazuo Morita" it is how a text addresses one of them, and
     * which is not known.
     */
    static boolean abbreviatesOne(String form, List<String> written) {
        String f = FamilyNames.withoutIndexNote(form).replaceAll("\\s*[(（][^)）]*[)）]\\s*$", "").strip();
        Set<String> fits = new HashSet<>();
        for (String w : written) {
            String x = FamilyNames.withoutIndexNote(w == null ? "" : w).replaceAll("\\s*[(（][^)）]*[)）]\\s*$", "").strip();
            if (x.isEmpty() || FamilyNames.initials(x) || !initialFits(f, x)) continue;
            fits.add(Vocabulary.norm(FamilyNames.comparable(x)));
        }
        return fits.size() == 1;
    }

    /** Whether "Mrs. <name>" writes a person by the name of somebody the claims say she married. */
    private static boolean husbandsName(List<Finding> shelf, String person, String name) {
        String his = mrsWith(name);
        if (his == null) return false;
        String me = Vocabulary.norm(person);
        Set<String> keys = FamilyNames.keys(his);
        for (Finding f : shelf) {
            Finding.Triple t = f.triple();
            if (t == null || !"married-to".equals(Vocabulary.norm(t.predicate()).replace(' ', '-'))) continue;
            String other = Vocabulary.norm(t.subject()).equals(me) ? t.object() : Vocabulary.norm(t.object()).equals(me) ? t.subject() : null;
            if (other != null && !Collections.disjoint(FamilyNames.keys(other), keys)) return true;
        }
        return false;
    }

    private static boolean hasSource(Finding held, List<Finding.Source> sources) {
        for (Finding.Source s : sources == null ? List.<Finding.Source>of() : sources) for (Finding.Source h : held.sources()) if (Evidence.sameLocator(h.locator(), s.locator())) return true;
        return false;
    }

    /**
     * Whether a new reading of a name says nothing the held claim's reading does not: no kind, year, end, event or words for how that the
     * held one lacks or gives otherwise. The forms are not counted: a new form of the same name is kept with the held claim.
     */
    static boolean addsNothing(Map<String, String> held, Map<String, String> now) {
        String k = now.getOrDefault("kind", "unknown");
        if (!k.equals("unknown") && !k.equals(held.isEmpty() ? "unknown" : held.getOrDefault("kind", "unknown"))) return false;
        for (String key : List.of("from", "to")) {
            String a = now.getOrDefault(key, ""), b = held.getOrDefault(key, "");
            if (a.isBlank() || a.equals(b)) continue;
            FamilyDate x = FamilyDate.parse(a), y = FamilyDate.parse(b);
            if (x == null || y == null || FamilyDate.apart(x, y, 0)) return false;
        }
        String e = now.getOrDefault("event", "");
        if (!e.isBlank() && !e.equals(held.getOrDefault("event", ""))) return false;
        return now.getOrDefault("said", "").isBlank() || !held.getOrDefault("said", "").isBlank();
    }

    /**
     * Whether a new reading of a name gives everything the held claim's reading gives: its kind, its years and its event. A source that gives
     * the name alone beside a claim that gives how and when it came says less, and joining it would make it back what it never said.
     */
    static boolean lacksNothing(Map<String, String> held, Map<String, String> now) {
        String k = held.isEmpty() ? "unknown" : held.getOrDefault("kind", "unknown");
        if (!k.equals("unknown") && !k.equals(now.getOrDefault("kind", "unknown"))) return false;
        for (String key : List.of("from", "to", "event")) if (!held.getOrDefault(key, "").isBlank() && now.getOrDefault(key, "").isBlank()) return false;
        return true;
    }

    /**
     * Names nodes.md marks as values, in one write: a name claim's object is a fact about the person beside it, shown next to them and never
     * walked through, so two people of one name are not joined through it. A value the file already describes keeps its line.
     */
    static void markValue(LibraryStore store, List<String> values) throws IOException {
        Vocabulary nodes = Vocabulary.read(Graph.nodesFile(store));
        boolean changed = false;
        for (String v : values) {
            String id = Vocabulary.norm(v);
            if (id.isEmpty() || nodes.get(id) != null || nodes.resolve(v) != null) continue;
            nodes.put(new Vocabulary.Term(id, "value: " + v.strip(), List.of(), ""));
            changed = true;
        }
        if (changed) Graph.writeNodes(store, nodes);
    }

    // ── a decision about a name ───────────────────────────────────────────────────────────────────────────────────────

    /** The wordings of {@link #PREDICATE} as genealogy's relations list them, folded, with the slug itself. */
    private static volatile Set<String> nameWords;

    private static Set<String> nameWords() {
        Set<String> w = nameWords;
        if (w != null) return w;
        Set<String> out = new HashSet<>(Set.of(PREDICATE, PREDICATE.replace('-', ' ')));
        for (Vocabulary.Term t : new GenealogyProfile().predicates()) if (t.slug().equals(PREDICATE)) for (String a : t.also()) out.add(Vocabulary.norm(a));
        nameWords = out;
        return out;
    }

    /** Whether a claim is a name claim by its relation as written: {@code has-name} or one of its wordings (a maiden name, a pen name). */
    public static boolean isNameClaim(Finding f) { return f != null && f.triple() != null && nameWords().contains(Vocabulary.norm(f.triple().predicate())); }

    /**
     * A name claim of genealogy's own the person disputed or retired: the other names only its own sources gave the person are taken off
     * again, from the person and from each entry joined into them. One that another standing name claim of the person, a merge, another
     * source's row or the owner's own hand gives stays, with its rows. The person's own label always stays.
     */
    public static void onDecision(LibraryStore store, Finding f) throws IOException {
        if (!isNameClaim(f) || (f.state() != Finding.State.disputed && f.state() != Finding.State.retired)) return;
        // genealogy's own name claims only, told by provenance: an ordinary claim worded like a name ("also known as", "pen name") is the
        // ordinary library's, and its other names are nobody's business here
        if (!Fields.ofClaim(store, f).contains(new GenealogyProfile().name())) return;
        Graph g = FamilyPeople.view(store);
        // the entry the read wrote the other names to is the one its words lead to by name (the list of names is kept by name); the person
        // is the one the claim is linked to, and another standing name claim of theirs keeps a name
        String person = g.nodeIdOf(f.triple().subject()), linked = g.nodeOf(f, true);
        Graph.Node node = g.node(person);
        String label = node != null ? node.label() : f.triple().subject();
        List<String> gave = new ArrayList<>();
        gave.add(written(f));
        for (Form x : formsOf(FamilyDetail.get(f, "forms"))) gave.add(x.text());
        Set<String> kept = new HashSet<>();
        for (Finding other : g.store().scanFindings().findings()) {
            if (other.id().equals(f.id()) || !isNameClaim(other) || other.state() == Finding.State.disputed || other.state() == Finding.State.retired || other.state() == Finding.State.superseded) continue;
            if (!g.nodeIdOf(other.triple().subject()).equals(person) && !g.nodeOf(other, true).equals(linked)) continue;
            kept.add(Vocabulary.norm(written(other)));
            for (Form x : formsOf(FamilyDetail.get(other, "forms"))) kept.add(Vocabulary.norm(x.text()));
        }
        // the entries joined into the person keep their own names, and a form this claim gave goes from their terms too, where the join left it
        List<String> terms = new ArrayList<>(List.of(label));
        for (Map.Entry<String, String> m : Graph.merges(store).entrySet()) {
            if (!g.nodeIdOf(m.getValue()).equals(person)) continue;
            kept.add(m.getKey());
            Vocabulary.Term t = g.curated().get(m.getKey());
            if (t != null && !terms.contains(Graph.labelOf(t.description()))) terms.add(Graph.labelOf(t.description()));
        }
        // where each other name came from: one another source gave, or that no source row explains (typed by hand, or older than the rows),
        // stays with its rows. Only an other name that this claim's own sources alone gave goes
        Map<String, List<String>> rows = new HashMap<>();
        for (Map.Entry<String, List<String>> r : Graph.aliasSources(store, label).entrySet()) rows.computeIfAbsent(Vocabulary.norm(r.getKey()), x -> new ArrayList<>()).addAll(r.getValue());
        List<String[]> drop = new ArrayList<>();
        for (String form : gave) {
            String k = Vocabulary.norm(form);
            if (k.isEmpty() || k.equals(Vocabulary.norm(label)) || kept.contains(k)) continue;
            List<String> from = rows.getOrDefault(k, List.of());
            if (from.isEmpty() || from.stream().anyMatch(src -> f.sources().stream().noneMatch(s -> Evidence.sameLocator(s.locator(), src)))) continue;
            for (String t : terms) drop.add(new String[]{t, form});
        }
        if (!drop.isEmpty()) Graph.dropAliases(store, drop);
    }

    // ── names over a life ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * One name of a person over their life, worked out from the claims that give it. {@code from}/{@code to}: the years it was carried as
     * far as the claims say (null where they do not); {@code fromEvent}: its from is the date of the event that caused it. {@code claims}:
     * the claims that give it, best first. {@code evidence}: the best of them. {@code accepted}: the family's own word is among them.
     * {@code implicit}: no claim gives it; it is read from the person's label and other names. {@code workedOut}: its kind or its from were
     * worked out from the other claims, not filed; {@code basis} says from what ({@link #BASIS_MARRIAGE} and the others; "" when nothing was
     * worked out). {@code familyReadings}: the readings the library's sources give its family part in characters (a family's romanised name,
     * a claim's kana), as romaji keys; empty where none is known. Other classes read {@link #explained}, {@link #dated} and {@link #settled}
     * to know whether anything is left to ask.
     */
    public record Name(String written, String family, String given, List<Form> forms, String kind, String said,
                       FamilyDate from, boolean fromEvent, FamilyDate to, String event, List<String> claims,
                       Evidence evidence, boolean accepted, boolean implicit, boolean workedOut, String basis, Set<String> familyReadings) {

        /** A name as the claims give it, or as a caller builds one: worked out from a marriage when {@code workedOut}, as before there were other bases. */
        public Name(String written, String family, String given, List<Form> forms, String kind, String said, FamilyDate from, boolean fromEvent, FamilyDate to, String event,
                    List<String> claims, Evidence evidence, boolean accepted, boolean implicit, boolean workedOut) {
            this(written, family, given, forms, kind, said, from, fromEvent, to, event, claims, evidence, accepted, implicit, workedOut, workedOut ? BASIS_MARRIAGE : "");
        }

        /** A name with no readings of its family part known beside its own forms. */
        public Name(String written, String family, String given, List<Form> forms, String kind, String said, FamilyDate from, boolean fromEvent, FamilyDate to, String event,
                    List<String> claims, Evidence evidence, boolean accepted, boolean implicit, boolean workedOut, String basis) {
            this(written, family, given, forms, kind, said, from, fromEvent, to, event, claims, evidence, accepted, implicit, workedOut, basis, Set.of());
        }

        public Name {
            basis = basis == null ? "" : basis;
            familyReadings = familyReadings == null ? Set.of() : Set.copyOf(familyReadings);
        }

        public boolean replaces() { return FamilyNameHistory.replaces(kind); }

        /**
         * Whether how the name came is known: a claim gives its kind, or the evidence settles it ({@link #workedOut}: a marriage, the entry
         * into a family, a birth parent's family name). A name that is not explained is the question of how it came.
         */
        public boolean explained() { return !"unknown".equals(kind); }

        /**
         * Whether the library worked out that the name came with a marriage, as nothing points to more: shown so, and one answer changes it
         * ({@code genealogy who} with the person's name asks how it came).
         */
        public boolean cameWithTheMarriage() { return workedOut && "marriage".equals(kind) && BASIS_MARRIAGE.equals(basis); }

        /** Whether the year the name was taken is known: a claim gives it, it follows the event that caused it, or the evidence dates it. */
        public boolean dated() { return from != null || fromEvent; }

        /**
         * Whether the evidence settles this name, so that nothing about it is to be asked: its kind is known, and so is its year, or it needs
         * none (the name at birth, a name carried alongside the others). A name worked out from the evidence counts as settled; the
         * library shows it as worked out ({@link #workedOutWords}).
         */
        public boolean settled() { return explained() && (dated() || kind.equals("birth") || !replaces()); }

        /**
         * How the name was worked out, for a person to read after the name: "worked out from the marriage", "the year worked out from the
         * marriage", "worked out from entering the family", "the year worked out from entering the family", "worked out from a birth parent's
         * family name, as the name at birth". "" for a name the claims give as it is.
         */
        public String workedOutWords() {
            if (!workedOut) return "";
            return switch (basis) {
                case BASIS_MARRIAGE_YEAR -> "the year worked out from the marriage";
                case BASIS_ENTRY -> "worked out from entering the family";
                case BASIS_ENTRY_YEAR -> "the year worked out from entering the family";
                case BASIS_BIRTH -> "worked out from a birth parent's family name, as the name at birth";
                case BASIS_FORMERLY -> "worked out from the words that say which name came first";
                default -> "worked out from the marriage";
            };
        }

        /**
         * Whether the name is a Japanese one: it or a form of it is written in characters or kana. Its forms in Latin letters are then romaji and
         * compared as romaji. A tag alone does not make it one: before 0.5.0 every form in Latin letters was tagged ja-Latn.
         */
        public boolean japanese() {
            if (FamilyForms.japanese(written) || FamilyForms.japanese(family)) return true;
            for (Form f : forms) if (FamilyForms.japanese(f.text())) return true;
            return false;
        }

        /** The name in a script ("han", "kana", "latin"), when one of its forms is written in it; else as written. */
        public String shown(String script) {
            if (script == null || script.isBlank() || FamilyForms.script(written).equals(script)) return written;
            for (Form f : forms) if (FamilyForms.script(f.text()).equals(script)) return f.text();
            return written;
        }

        /** The name and every form it has, as written. */
        public List<String> texts() {
            List<String> out = new ArrayList<>(List.of(written));
            for (Form f : forms) if (!out.contains(f.text())) out.add(f.text());
            return out;
        }

        /**
         * The readings among its forms: the kana and the Latin forms a source gave this name, through which this name's own characters meet
         * another script ({@link FamilyForms#sameForm(String, String, Collection)} with one side a form of this name).
         */
        public List<String> readings() {
            List<String> out = new ArrayList<>();
            for (String t : texts()) if (!FamilyForms.script(t).equals("han")) out.add(t);
            return out;
        }

        /**
         * Whether a written name is a form of this name: one of its forms in the same script ({@link FamilyForms#sameForm}), or its kana or
         * romaji. Its own kana and romaji forms are the readings a source gave it, so a form in characters matches only its own characters.
         */
        public boolean isForm(String s) {
            if (s == null || s.isBlank()) return false;
            boolean jp = japanese();
            for (String t : texts()) if (t.equals(s.strip()) || FamilyForms.sameForm(s, t, jp) || initialFits(s, t)) return true;
            return false;
        }

        /**
         * Whether this name's family part is this one, in any script its forms are written in: its own family part, the word of a romaji
         * form that is the family name, or the beginning of a kana form.
         */
        public boolean hasFamily(String f) {
            if (f == null || f.isBlank()) return false;
            boolean jp = japanese();
            String sc = FamilyForms.script(f);
            if (!family.isBlank()) {
                // the family part is known: it alone is compared, in its own script. A given name that is also a family name (Ellis Hale) bears no Ellis
                if (FamilyForms.sameForm(family, f, jp)) return true;
                if (FamilyForms.script(family).equals(sc)) return false;
                // a reading the library's sources give the family part itself (a family's romanised name, a claim's kana), for any name of
                // that family part: 森田 read Morita
                if (sc.equals("latin") && familyReadings.contains(FamilyForms.latinKey(f))) return true;
                // in another script, through the forms a source gave WITH this name; an implicit name's other forms were only placed beside it
                if (implicit) return false;
                // a word in letters is the family part where a reading a source gave says so. Where no source reads the family part at all,
                // which word of the name's own romanised form ("Kenji /Endo/" of a tree file) is the family's is not known, and either may be:
                // the name is then a candidate a question offers, never a link made alone
                if (sc.equals("latin")) {
                    List<String> own = ownReadings();
                    for (String r : own) if (FamilyForms.sameForm(r, f, true)) return true;
                    if (!own.isEmpty() || !familyReadings.isEmpty()) return false;
                }
                for (String t : texts()) {
                    if (!FamilyForms.script(t).equals(sc)) continue;
                    if (sc.equals("latin")) { for (String w : words(t)) if (FamilyForms.sameForm(w, f, jp)) return true; }
                    else if (sc.equals("kana")) { if (FamilyForms.kanaKey(t).startsWith(FamilyForms.kanaKey(f))) return true; }
                }
                return false;
            }
            // the split is not known: any word of a form in letters, the beginning of a kana form, a name in characters that begins with it, or a
            // word of one written with a space. An implicit name counts only the forms in its own script: the others were placed beside it
            String own = FamilyForms.script(written);
            for (String t : texts()) {
                if (!FamilyForms.script(t).equals(sc) || (implicit && !FamilyForms.script(t).equals(own))) continue;
                if (sc.equals("latin") || sc.isEmpty()) { for (String w : words(t)) if (FamilyForms.sameForm(w, f, jp)) return true; }
                else if (sc.equals("kana")) { if (FamilyForms.kanaKey(t).startsWith(FamilyForms.kanaKey(f))) return true; }
                else if (sc.equals("han")) {
                    if (t.strip().matches("\\S+[\\s　]+\\S+")) { for (String w : t.strip().split("[\\s　]+")) if (FamilyForms.hanKey(w).equals(FamilyForms.hanKey(f))) return true; }
                    else if (FamilyForms.hanKey(t).startsWith(FamilyForms.hanKey(f)) && FamilyForms.hanKey(t).length() > FamilyForms.hanKey(f).length()) return true;
                }
            }
            return false;
        }

        /**
         * The readings its sources give its family part in characters: the first word of each of its kana forms written in words (もりた
         * けんじ reads 森田 as もりた), when the name begins with that family part. Empty when no source gave a reading.
         */
        private List<String> ownReadings() {
            List<String> out = new ArrayList<>();
            if (!FamilyForms.script(family).equals("han") || !FamilyForms.hanKey(written).startsWith(FamilyForms.hanKey(family))) return out;
            for (Form x : forms) {
                if (!FamilyForms.script(x.text()).equals("kana")) continue;
                String[] ws = x.text().strip().split("[\\s　・]+");
                if (ws.length >= 2) out.add(ws[0]);
            }
            return out;
        }

        /** The words for its kind ({@link #KINDS}). */
        public String kindWords() { return KINDS.getOrDefault(kind, KINDS.get("unknown")); }
    }

    /** The bases of a name worked out from the evidence ({@link Name#basis}). Stable values other classes may test. */
    public static final String BASIS_MARRIAGE = "marriage", BASIS_MARRIAGE_YEAR = "marriage-year", BASIS_ENTRY = "entry", BASIS_ENTRY_YEAR = "entry-year", BASIS_BIRTH = "birth", BASIS_FORMERLY = "formerly";

    /** The words of a form in letters: "Endō Kenji" is Endō and Kenji, "Hale, Mary" Hale and Mary. */
    static List<String> words(String form) {
        List<String> out = new ArrayList<>();
        for (String w : (form == null ? "" : form).split("[\\s,，]+")) if (!w.isBlank()) out.add(w.strip());
        return out;
    }

    /** An initial and the word it begins: "M." and "Mary". */
    private static boolean initial(String a, String b) {
        String x = a.replaceAll("\\.$", ""), y = b.replaceAll("\\.$", "");
        if (x.codePointCount(0, x.length()) != 1 || y.codePointCount(0, y.length()) < 2) return false;
        return FamilyForms.plainKey(x).equals(FamilyForms.plainKey(y.substring(0, y.offsetByCodePoints(0, 1))));
    }

    /**
     * Whether two forms in letters are one name written once with an initial: "M. Ellis" and "Mary Ellis". The same words but for words
     * that are an initial of each other, never two whole words that differ.
     */
    static boolean initialFits(String a, String b) {
        if (a == null || b == null || !FamilyForms.script(a).equals("latin") || !FamilyForms.script(b).equals("latin")) return false;
        List<String> x = new ArrayList<>(words(a)), y = new ArrayList<>(words(b));
        if (x.size() != y.size() || x.size() < 2) return false;
        for (Iterator<String> it = x.iterator(); it.hasNext(); ) {
            String w = it.next();
            for (int i = 0; i < y.size(); i++) if (FamilyForms.plainKey(y.get(i)).equals(FamilyForms.plainKey(w))) { y.remove(i); it.remove(); break; }
        }
        if (x.isEmpty() || x.size() == words(a).size()) return false;
        for (String w : x) {
            int at = -1;
            for (int i = 0; i < y.size() && at < 0; i++) if (initial(w, y.get(i)) || initial(y.get(i), w)) at = i;
            if (at < 0) return false;
            y.remove(at);
        }
        return y.isEmpty();
    }

    private static final Map<Graph, Index> MEMO = Collections.synchronizedMap(new WeakHashMap<>());

    /** The names of everybody in a graph, worked out on demand; one per graph, from one reading of the claims. */
    public static Index of(Graph g) {
        Index i = MEMO.get(g);
        if (i != null) return i;
        i = new Index(g);
        MEMO.put(g, i);
        return i;
    }

    /** A claim as a name: what one claim says of it, and how much that claim counts. */
    private record Said(Finding f, String written, String family, String given, List<Form> forms, String kind, String said, FamilyDate from, boolean fromEvent,
                        FamilyDate to, String event, int rank, Evidence evidence, boolean accepted) {
        boolean japanese() {
            if (FamilyForms.japanese(written) || FamilyForms.japanese(family)) return true;
            for (Form x : forms) if (FamilyForms.japanese(x.text())) return true;
            return false;
        }
    }

    /**
     * A family a person entered, as a claim says how: the family's name, the kind of name the entry gives, its date, the claim, and whether
     * that claim is the family's own word (accepted by the person).
     */
    private record Entry(String family, String kind, FamilyDate from, String claim, boolean word) { }

    /** The kind of name an entry into a family gives, by how the person came in; "" when the way does not say (unstated, by birth, a founding). */
    static String entryKind(String how) {
        return switch (how == null ? "" : how.strip().toLowerCase(Locale.ROOT)) {
            case "mukoyoshi" -> "mukoyoshi";
            case "nyufu" -> "nyufu";
            case "adoption", "adoptive", "adopted" -> "adoptive";
            case "marriage" -> "marriage";
            case "succession" -> "succession";
            default -> "";
        };
    }

    /** The kind of name an adoption gives, by the kind of the adoption: 婿養子 its own, an heir's or an ordinary adoption a name on adoption; "" when not said. */
    static String adoptedKind(String adoption) {
        return switch (adoption == null ? "" : adoption) {
            case "mukoyoshi" -> "mukoyoshi";
            case "heir", "ordinary" -> "adoptive";
            default -> "";
        };
    }

    /** The names of the people of one graph ({@link #of}). */
    public static final class Index {
        private final Graph g;
        private final Map<String, Finding> byId = new HashMap<>();
        private final Map<String, List<Finding>> nameClaims = new HashMap<>();
        private final Map<String, List<String>> about = new HashMap<>();
        private final Map<String, List<String[]>> spouses = new HashMap<>();   // person → {spouse, claim}
        private final Map<String, List<String[]>> memberships = new HashMap<>();   // person → {family id, claim}
        private final Map<String, List<String[]>> adoptions = new HashMap<>();   // person → {adoptive parent, claim}
        private final Map<String, List<Finding>> withdrawn = new HashMap<>();   // person → the name claims the person disputed or retired
        private final Map<String, List<String>> parents = new HashMap<>();   // child → birth parents
        private final Map<String, FamilyDate> born;
        private final Map<String, FamilyDate> died = new HashMap<>();
        private final Map<String, List<String>> heirOf = new HashMap<>();   // person → the people they are recorded as heir of
        private final Set<String> familyParts = new LinkedHashSet<>();
        private final Map<String, List<Name>> names = new HashMap<>();
        private final Map<String, int[]> windows = new HashMap<>();
        private final Set<String> working = new HashSet<>();
        private volatile List<String> guessed;
        private volatile Map<String, Set<String>> readings;
        private final Map<String, List<String>> joined = new HashMap<>();   // a person → the entries joined into them (merges.tsv), read once

        Index(Graph g) {
            this.g = g;
            List<Finding> all = FamilyPeople.findings(g);   // the claims the view was read from: one reading of the shelves for both
            for (Finding f : all) byId.put(f.id(), f);
            for (Graph.Edge e : g.edges()) {
                if (e.predicate().equals(PREDICATE) && (e.disputed() || e.state().equals("retired")) && byId.get(e.findingId()) != null)
                    withdrawn.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(byId.get(e.findingId()));
                if (FamilyKin.gone(e) || e.predicate().equals("is filed under") || e.predicate().equals("mentions")) continue;
                Finding f = byId.get(e.findingId());
                if (f == null) continue;
                about.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.findingId());
                switch (e.predicate()) {
                    case PREDICATE -> {
                        nameClaims.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(f);
                        String fam = FamilyDetail.get(f, "family");
                        if (!fam.isBlank()) familyParts.add(fam.strip());
                    }
                    case "married-to" -> {
                        spouses.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(new String[]{e.to(), f.id()});
                        spouses.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(new String[]{e.from(), f.id()});
                    }
                    case FamilyHouses.MEMBER -> { if (FamilyHouses.isFamily(g, e.to())) memberships.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(new String[]{e.to(), f.id()}); }
                    case "adopted-by" -> adoptions.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(new String[]{e.to(), f.id()});
                    case "heir-of" -> heirOf.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to());
                    case "died-on" -> { FamilyDate d = FamilyDate.parse(f.triple().object()); if (d != null) died.putIfAbsent(e.from(), d); }
                    case "died-in", "buried-in" -> { FamilyDate d = FamilyChecks.claimDate(f); if (d != null) died.putIfAbsent(e.from(), d); }
                    default -> { }
                }
            }
            for (Graph.Node n : g.nodes()) if ("family".equals(n.kind())) { String nm = FamilyHouses.nameOf(n.label()); if (!nm.isBlank()) familyParts.add(nm); }
            for (Vocabulary.Term t : g.curated().terms().values()) if ("family".equals(Graph.kindOf(t.description()))) { String nm = FamilyHouses.nameOf(Graph.labelOf(t.description())); if (!nm.isBlank()) familyParts.add(nm); }
            FamilyKin.parents(g).forEach((child, links) -> { List<String> ps = new ArrayList<>(); for (FamilyKin.Link l : links) if (!ps.contains(l.other())) ps.add(l.other()); parents.put(child, ps); });
            born = Gedcom.birthDates(g, all);
            Map<String, String> m;
            try { m = Graph.merges(g.store()); } catch (IOException e) { m = Map.of(); }
            for (String from : m.keySet()) { String to = g.nodeIdOf(from); if (!to.equals(from)) joined.computeIfAbsent(to, k -> new ArrayList<>()).add(from); }
        }

        /** The graph the index was read from. */
        public Graph graph() { return g; }

        /** A claim of the library by its id, or by its short code (F-0012), from the one reading of the claims; null when there is none. */
        public Finding finding(String id) {
            if (id == null || id.isBlank()) return null;
            Finding f = byId.get(id.strip());
            if (f != null) return f;
            for (Finding x : byId.values()) if (x.id().startsWith(id.strip() + "-")) return x;
            return null;
        }

        /** The family parts the library knows as family parts: those of the name claims, and the names of the families. */
        public Set<String> familyParts() { return Collections.unmodifiableSet(familyParts); }

        /** A person's birth date as the claims give it; null when none does. */
        public FamilyDate born(String id) { return born.get(id); }

        /** A person's death as the claims date it; null when none does. */
        public FamilyDate died(String id) { return died.get(id); }

        /**
         * A person's names, in order: the name at birth first, then by the year each was taken, then those whose year is not known; each
         * with the years it was carried as far as the claims say, and each whose kind or year the evidence settles worked out. Empty for a
         * node the graph does not have.
         */
        public synchronized List<Name> names(String id) {
            List<Name> have = names.get(id);
            if (have != null) return have;
            // somebody whose names are being worked out, asked about again on the way (a husband's name at the marriage, a parent's at the
            // birth, and round again): their names as the claims give them, worked out no further, so the working out ends
            if (working.contains(id)) return withReadings(ordered(given(id)));
            working.add(id);
            try {
                List<Name> out = withReadings(ordered(explained(id, given(id))));
                names.put(id, out);
                return out;
            } finally {
                working.remove(id);
            }
        }

        /**
         * The names, each with the readings the library's sources give its family part in characters ({@link #readingsOf}), and the family
         * word the person's own names tell apart in letters: two names of one given part in characters (遠藤健二, 森田健二) whose forms in
         * letters share one word (Kenji Endo, Morita Kenji) read their family parts in the words they differ in (Endo, Morita).
         */
        private List<Name> withReadings(List<Name> ns) {
            List<Name> out = new ArrayList<>();
            for (Name n : ns) {
                if (!FamilyForms.script(n.family()).equals("han")) { out.add(n); continue; }
                Set<String> r = new LinkedHashSet<>(readingsOf(n.family()));
                for (Name o : ns) {
                    if (o == n || !FamilyForms.script(o.family()).equals("han") || FamilyForms.sameForm(o.family(), n.family())) continue;
                    if (n.given().isBlank() || !FamilyForms.sameForm(o.given(), n.given())) continue;
                    for (String t : n.texts()) for (String u : o.texts()) {
                        String w = differing(t, u);
                        if (w != null) r.add(FamilyForms.latinKey(w));
                    }
                }
                out.add(r.isEmpty() ? n : new Name(n.written(), n.family(), n.given(), n.forms(), n.kind(), n.said(), n.from(), n.fromEvent(), n.to(), n.event(), n.claims(), n.evidence(), n.accepted(), n.implicit(), n.workedOut(), n.basis(), r));
            }
            return out;
        }

        /** Of two forms in letters of two words each that share exactly one word, the other word of the first; null otherwise. */
        private static String differing(String t, String u) {
            if (!FamilyForms.script(t).equals("latin") || !FamilyForms.script(u).equals("latin")) return null;
            List<String> a = words(t), b = words(u);
            if (a.size() != 2 || b.size() != 2) return null;
            String other = null;
            int shared = 0;
            for (String w : a) {
                if (b.stream().anyMatch(x -> FamilyForms.sameForm(x, w, true))) shared++;
                else other = w;
            }
            return shared == 1 ? other : null;
        }

        /** The latest name: the replacing name taken last; the label's name when no later name is dated; the birth name when there is no other. */
        public Name latest(String id) {
            List<Name> ns = names(id);
            if (ns.isEmpty()) return null;
            List<Name> later = ns.stream().filter(n -> n.replaces() && !n.kind().equals("birth")).toList();
            List<Name> dated = later.stream().filter(n -> n.from() != null).toList();
            if (!dated.isEmpty()) return dated.stream().max(Comparator.comparingInt((Name n) -> n.from().year()).thenComparing(n -> -rankOf(n))).orElseThrow();
            Graph.Node node = g.node(id);
            if (!later.isEmpty()) {
                if (node != null) for (Name n : later) if (n.isForm(node.label())) return n;
                return later.get(0);
            }
            Name b = birth(id);
            if (b != null) return b;
            if (node != null) for (Name n : ns) if (n.isForm(node.label())) return n;
            return ns.get(0);
        }

        private static int rankOf(Name n) { return n.accepted() ? 0 : n.implicit() ? 9 : n.evidence() == null ? 5 : n.evidence().ordinal() + 1; }

        /** The name the person was born with: a name of kind birth; null when no claim gives one and the evidence does not settle one. */
        public Name birth(String id) {
            for (Name n : names(id)) if (n.kind().equals("birth")) return n;
            return null;
        }

        /**
         * The name the person carried in a year: the replacing name whose years hold it, from its year up to its end, or on when no name
         * that could have followed it is left undated; a name taken that year rather than the name at birth; else the one name whose records
         * of that year are written under it; else null, because the evidence does not say.
         */
        public Name at(String id, int year) {
            List<Name> ns = names(id);
            List<Name> replacing = ns.stream().filter(Name::replaces).toList();
            Name best = null;
            for (int i = 0; i < replacing.size(); i++) {
                Name n = replacing.get(i);
                // a name holds from its year on; the name at birth from the start
                boolean start = n.from() != null ? n.from().year() <= year : n.kind().equals("birth");
                if (!start) continue;
                // and up to its end; a name whose end the claims do not give holds on unless a later name without a year could have followed it:
                // one the order puts after it, that did not end before it began
                boolean end;
                if (n.to() != null) end = year < n.to().year();
                else {
                    end = true;
                    for (int k = i + 1; k < replacing.size() && end; k++) {
                        Name m = replacing.get(k);
                        if (m.from() != null || m.kind().equals("birth")) continue;
                        if (m.to() != null && n.from() != null && m.to().year() <= n.from().year()) continue;
                        end = false;
                    }
                }
                if (!end) continue;
                if (best == null || later(n, best)) best = n;
            }
            if (best != null) return best;
            Name used = null;
            for (Name n : ns) {
                int[] w = useWindow(id, n);
                if (w == null || year < w[0] || year > w[1]) continue;
                if (used != null) return null;
                used = n;
            }
            return used;
        }

        /** Whether a name began after another; of two taken in one year, the one that is not the name at birth. */
        private static boolean later(Name n, Name than) {
            if (n.from() == null) return false;
            if (than.from() == null) return true;
            if (n.from().year() != than.from().year()) return n.from().year() > than.from().year();
            return than.kind().equals("birth") && !n.kind().equals("birth");
        }

        /** The heading a page shows: the latest name, with the birth family part beside it when that differs ("森田健二 (born 遠藤)"), or the whole birth name when the given part differs too. */
        public String heading(String id) {
            Graph.Node node = g.node(id);
            String script = node == null ? "" : FamilyForms.script(node.label());
            Name latest = latest(id);
            if (latest == null) return node == null ? id : node.label();
            // a title or a one-word name (a courtesy name, a given name alone) never heads a person who has a full name: the full name does,
            // with the entry's own name after it, so the entry is still found by it. An entry of a family name alone is somebody of that family
            // whom the source does not name: no full name heads it
            boolean familyAlone = node != null && aFamilyWord(id, FamilyNames.comparable(withoutNamesake(node.label())), List.of());
            if (!familyAlone && (!whole(id, latest) || allTitled(latest) || formOnly(id, latest))) { Name w = wholeName(id); if (w != null && node != null) return w.shown(script) + " (" + node.label() + ")"; }
            String shown = latest.shown(script);
            Name b = birth(id);
            if (b == null || b == latest || b.isForm(latest.written())) return shown;
            String bf = b.family(), lf = latest.family();
            String bShown = b.shown(script);
            boolean jp = b.japanese() || latest.japanese();
            if (!bf.isBlank() && !lf.isBlank()) {
                boolean sameFamily = FamilyForms.sameForm(bf, lf, jp);
                boolean sameGiven = !b.given().isBlank() && !latest.given().isBlank() && FamilyForms.sameForm(b.given(), latest.given(), jp);
                if (sameFamily && sameGiven) return shown;
                if (sameGiven) return shown + " (born " + familyShown(id, b, script) + ")";
            }
            return shown + " (born " + bShown + ")";
        }

        /** A name's family part in a script: its own when written in it; else the family part of its form in that script. */
        private String familyShown(String id, Name n, String script) {
            if (FamilyForms.script(n.family()).equals(script) || script.isBlank()) return n.family();
            String form = n.shown(script);
            if (form.equals(n.written())) return n.family();
            String[] p = parts(id, form);
            return p[0].isBlank() ? n.family() : p[0];
        }

        /**
         * The name to write on a dated line: the name the person carried in that year; where the evidence does not say, the name the claim was
         * written under when it is one of the person's names; else the latest name. {@code year} null: no date.
         */
        public String nameAt(String id, Integer year, String asWritten) {
            Graph.Node node = g.node(id);
            String script = node == null ? "" : FamilyForms.script(node.label());
            Name n = year == null ? null : at(id, year);
            if (n != null) return n.shown(script);
            if (asWritten != null && !asWritten.isBlank()) for (Name x : names(id)) if (x.isForm(asWritten)) return asWritten.strip();
            Name latest = latest(id);
            return latest != null ? latest.shown(script) : node == null ? (asWritten == null ? id : asWritten) : node.label();
        }

        /**
         * The first and last year of the dated records written under a name: claims about the person that rest on a record or on something
         * published, whose subject as written is a form of the name, and more than its given name alone ({@link #partOnly}). A family's
         * account, a book or a tree is a clue and dates no use. Null when no such record is dated.
         */
        public synchronized int[] useWindow(String id, Name n) {
            String key = id + "\u0000" + n.written() + "\u0000" + n.kind() + "\u0000" + (n.from() == null ? "" : n.from().year());
            if (windows.containsKey(key)) return windows.get(key);
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            for (String fid : about.getOrDefault(id, List.of())) {
                Finding f = byId.get(fid);
                if (f == null || f.triple() == null || isNameClaim(f)) continue;
                Evidence e = Evidence.of(f);
                if (e == Evidence.clue) continue;
                if (!n.isForm(f.triple().subject()) || partOnly(id, n, f.triple().subject())) continue;
                FamilyDate d = FamilyChecks.claimDate(f);
                if (d == null) continue;
                lo = Math.min(lo, d.year());
                hi = Math.max(hi, d.year());
            }
            int[] w = lo == Integer.MAX_VALUE ? null : new int[]{lo, hi};
            windows.put(key, w);
            return w;
        }

        /**
         * Whether a way of writing a name is only a part of it: its given name alone (Mary, 健二), which does not say which family name the
         * person carried then. The name as the entry writes it is never a part.
         */
        public boolean partOnly(String id, Name n, String written) {
            String t = withoutNamesake(written);
            if (FamilyForms.sameForm(t, withoutNamesake(n.written()), n.japanese())) return false;
            return FamilyForms.script(t).equals("han") ? givenAlone(id, n, t) : words(t).size() < 2;
        }

        /**
         * The years a name was carried, as far as anything says: its from and to, else its records' first and last year. {first, last}, each 0
         * where nothing says.
         */
        public int[] years(String id, Name n) {
            int[] w = useWindow(id, n);
            int from = n.from() != null ? n.from().year() : w != null ? w[0] : 0;
            int to = n.to() != null ? n.to().year() : w != null ? w[1] : 0;
            return new int[]{from, to};
        }

        /**
         * The people who bore a family name in a year: a name of theirs with that family part was theirs then by its years, or it is the only
         * name they are known by and the year falls in their life. People who took the name only later are not among them.
         */
        public List<String> bearers(String family, int year) {
            List<String> out = new ArrayList<>();
            if (family == null || family.isBlank()) return out;
            for (Graph.Node node : g.nodes()) {
                if (!"person".equals(node.kind())) continue;
                String id = node.id();
                FamilyDate b = born.get(id), d = died.get(id);
                if (b != null && b.earliest() > year) continue;
                if (d != null && d.latest() < year - 1) continue;
                List<Name> ns = names(id);
                Name then = at(id, year);
                boolean bore;
                if (then != null) bore = hasFamily(id, then, family);
                else {
                    List<Name> replacing = ns.stream().filter(Name::replaces).toList();
                    bore = replacing.size() == 1 && hasFamily(id, replacing.get(0), family) && (replacing.get(0).from() == null || replacing.get(0).from().year() <= year);
                }
                if (bore) out.add(id);
            }
            return out;
        }

        /**
         * Whether a name of a person has this family part: {@link Name#hasFamily}, the family part the library splits from the name when no
         * claim gives it, or, across scripts, a reading of its family part in characters that a source gave (a family's romanised name).
         */
        private boolean hasFamily(String id, Name n, String family) {
            if (n.hasFamily(family)) return true;
            String own = familyOf(id, n);
            if (own.isBlank()) return false;
            if (n.family().isBlank() && sameFamily(own, family, n.japanese())) return true;
            return !FamilyForms.script(own).equals(FamilyForms.script(family)) && sameFamily(own, family, true);
        }

        /**
         * Whether two family parts are one: forms of one another ({@link FamilyForms#sameForm}), or one in characters and the other a reading
         * of it a source gave ({@link #readingsOf}).
         */
        private boolean sameFamily(String a, String b, boolean romaji) {
            if (a == null || b == null || a.isBlank() || b.isBlank()) return false;
            if (FamilyForms.sameForm(a, b, romaji)) return true;
            boolean ha = FamilyForms.script(a).equals("han"), hb = FamilyForms.script(b).equals("han");
            if (ha == hb) return false;
            String han = ha ? a : b, other = ha ? b : a;
            Set<String> r = readingsOf(han);
            return !r.isEmpty() && r.contains(readingKey(other));
        }

        /** A reading or a romanised form as the readings are kept: its romaji key, long vowels folded (Endō, Endou and えんどう are one). */
        private static String readingKey(String s) {
            return FamilyForms.script(s).equals("kana") ? FamilyForms.latinKey(FamilyForms.hepburn(s)) : FamilyForms.latinKey(s);
        }

        /** A name's family part: the one its claims give; else the one the library splits from it; "" when not known. */
        private String familyOf(String id, Name n) {
            if (!n.family().isBlank()) return n.family();
            return split(id, n.written())[0];
        }

        /**
         * The readings the sources give of a family part in characters, as romaji keys: the names a family's entry is written in beside its
         * name in characters (森田家, the Morita family), and the kana reading a name claim gives with a name of that family part
         * (森田健二, もりた けんじ), or that the list of names gives beside a whole name in characters. Never a reading worked out from the
         * characters: 遠藤 and 円藤 are both えんどう.
         */
        Set<String> readingsOf(String han) {
            Map<String, Set<String>> r = readings;
            if (r == null) {
                r = new HashMap<>();
                List<List<String>> families = new ArrayList<>();
                for (String fid : FamilyHouses.all(g)) {
                    List<String> written = new ArrayList<>(List.of(FamilyHouses.labelOf(g, fid)));
                    written.addAll(FamilyHouses.aliasesOf(g, fid));
                    families.add(written);
                }
                for (List<String> written : families) {
                    List<String> hans = new ArrayList<>(), others = new ArrayList<>();
                    for (String w : written) {
                        String nm = FamilyHouses.familyName(w).strip();
                        if (nm.isEmpty() || FamilyHouses.familyWord(nm)) continue;
                        String sc = FamilyForms.script(nm);
                        if (sc.equals("han")) hans.add(nm); else if (sc.equals("kana") || sc.equals("latin")) others.add(nm);
                    }
                    for (String h : hans) for (String o : others) if (!readingKey(o).isEmpty()) r.computeIfAbsent(FamilyForms.hanKey(h), k -> new LinkedHashSet<>()).add(readingKey(o));
                }
                for (List<Finding> claims : nameClaims.values()) for (Finding f : claims) {
                    Map<String, String> d = FamilyDetail.of(f);
                    String fam = d.getOrDefault("family", "").strip(), w = written(f);
                    if (!FamilyForms.script(fam).equals("han") || !FamilyForms.hanKey(w).startsWith(FamilyForms.hanKey(fam))) continue;
                    for (Form x : formsOf(d.getOrDefault("forms", ""))) {
                        if (!FamilyForms.script(x.text()).equals("kana")) continue;
                        String[] ws = x.text().strip().split("[\\s　・]+");
                        if (ws.length >= 2) r.computeIfAbsent(FamilyForms.hanKey(fam), k -> new LinkedHashSet<>()).add(readingKey(ws[0]));
                    }
                }
                // and a reading on file beside a whole name in characters, in its list of names (森田健二, もりた けんじ): its first word reads the
                // family part the name begins with, a family part the claims give or else one the library guesses from its people (the reading
                // is still the source's; only where the characters part is guessed)
                for (Graph.Node n : g.nodes()) {
                    if (!"person".equals(n.kind()) || !FamilyForms.script(n.label()).equals("han")) continue;
                    String h = FamilyForms.hanKey(n.label());
                    String fam = null;
                    for (String fp : familyParts) if (FamilyForms.script(fp).equals("han") && h.startsWith(FamilyForms.hanKey(fp)) && h.length() > FamilyForms.hanKey(fp).length() && (fam == null || fp.length() > fam.length())) fam = fp;
                    if (fam == null) for (String fp : guessed()) if (FamilyForms.script(fp).equals("han") && h.startsWith(FamilyForms.hanKey(fp)) && h.length() > FamilyForms.hanKey(fp).length() && (fam == null || fp.length() > fam.length())) fam = fp;
                    if (fam == null) continue;
                    for (String a : n.aliases()) {
                        String[] ws = a.strip().split("[\\s　・]+");
                        if (ws.length == 2 && FamilyForms.script(a).equals("kana")) r.computeIfAbsent(FamilyForms.hanKey(fam), k -> new LinkedHashSet<>()).add(readingKey(ws[0]));
                    }
                }
                // and people written in characters and in Latin letters: each word of a person's Latin forms may read the family part in
                // characters their name begins with (遠藤健二, Kenji Endoh). A word two people of that family part agree on is its reading;
                // their given names differ, and agree on nothing
                Map<String, Map<String, Set<String>>> votes = new HashMap<>();   // family part in characters → romaji key → the people
                for (Graph.Node n : g.nodes()) {
                    if (!"person".equals(n.kind())) continue;
                    List<String> forms = new ArrayList<>(n.aliases());
                    forms.add(n.label());
                    String fam = null;
                    for (String f : forms) {
                        if (!FamilyForms.script(f).equals("han")) continue;
                        String h = FamilyForms.hanKey(f);
                        for (String fp : familyParts) if (FamilyForms.script(fp).equals("han") && h.startsWith(FamilyForms.hanKey(fp)) && h.length() > FamilyForms.hanKey(fp).length() && (fam == null || fp.length() > fam.length())) fam = fp;
                    }
                    if (fam == null) continue;
                    for (String f : forms) {
                        if (!FamilyForms.script(f).equals("latin")) continue;
                        for (String w : f.split("[^\\p{L}'’]+")) {
                            String k = readingKey(w);
                            if (k.length() >= 2) votes.computeIfAbsent(FamilyForms.hanKey(fam), x -> new HashMap<>()).computeIfAbsent(k, x -> new HashSet<>()).add(n.id());
                        }
                    }
                }
                for (Map.Entry<String, Map<String, Set<String>>> v : votes.entrySet())
                    for (Map.Entry<String, Set<String>> w : v.getValue().entrySet()) if (w.getValue().size() >= 2) r.computeIfAbsent(v.getKey(), k -> new LinkedHashSet<>()).add(w.getKey());
                readings = r;
            }
            return r.getOrDefault(FamilyForms.hanKey(han == null ? "" : han), Set.of());
        }

        /** Whether a word in letters is a family part the library knows: one a claim gives in letters, or a reading of one in characters. */
        private boolean knownFamilyWord(String w) {
            for (String p : familyParts) if (!FamilyForms.script(p).equals("han") && !FamilyForms.script(p).equals("kana") && FamilyForms.sameForm(p, w, true)) return true;
            String k = FamilyForms.latinKey(w);
            if (k.isEmpty()) return false;
            readingsOf("");   // read once
            for (Set<String> r : readings.values()) if (r.contains(k)) return true;
            return false;
        }

        /**
         * A name's family and given parts, {family, given}: from the name claims of the person that give this name; else, for a name in
         * characters or kana, the longest beginning that is a family part the library knows (a name claim's, a family's), else one of the
         * family names the library guesses from its people; for a name in letters, the word the library knows as a family name, the word
         * where it differs from another name of the person of another family part, else the last. {"", name} when the split is not known.
         */
        public String[] parts(String id, String name) {
            String n = name == null ? "" : name.strip();
            if (n.isEmpty()) return new String[]{"", ""};
            if (id != null && !id.isEmpty() && g.node(id) != null) {
                List<Name> ns = names(id);
                for (Name x : ns) if (!x.family().isBlank() && (x.written().equals(n) || FamilyForms.script(x.written()).equals(FamilyForms.script(n)) && x.isForm(n))) return new String[]{x.family(), x.given()};
                return split(id, n, ns);
            }
            return split(id, n);
        }

        /**
         * A split, and where the library knows no family part for the name, the person's own other names: a name in characters or kana
         * that ends with the given part of another of their names parts there (森田健二 beside 遠藤健二, given 健二, is 森田 and 健二); a
         * name in letters that is a form of one of their names and differs by one word from a form of another name of theirs, with another
         * family part and the same given part, parts at that word (Endo Kenji beside Morita Kenji: Endo).
         */
        private String[] split(String id, String n, List<Name> others) {
            String sc = FamilyForms.script(n);
            if (!sc.equals("han") && !sc.equals("kana")) {
                String[] w = byOtherNames(n, others);
                if (w != null) return w;
                return split(id, n);
            }
            String[] p = split(id, n);
            if (!p[0].isEmpty()) return p;
            String joined = n.replaceAll("[\\s　・]+", "");
            String given = "";
            for (Name x : others) {
                String gv = x.given().replaceAll("[\\s　・]+", "");
                if (x.family().isBlank() || gv.isEmpty() || gv.length() >= joined.length() || !FamilyForms.script(gv).equals(sc)) continue;
                String tail = joined.substring(joined.length() - gv.length());
                boolean ends = sc.equals("han") ? FamilyForms.hanKey(tail).equals(FamilyForms.hanKey(gv)) : FamilyForms.kanaKey(tail).equals(FamilyForms.kanaKey(gv));
                if (ends && gv.length() > given.length()) given = gv;
            }
            return given.isEmpty() ? p : new String[]{joined.substring(0, joined.length() - given.length()), joined.substring(joined.length() - given.length())};
        }

        /** The family word of a form in letters where it and a form of another of the person's names part (see {@link #split(String, String, List)}); null when none says. */
        private static String[] byOtherNames(String n, List<Name> others) {
            List<String> mine = words(n);
            if (mine.size() != 2 || others == null) return null;
            Name own = null;
            for (Name x : others) if (x.isForm(n)) { own = x; break; }
            if (own == null || own.family().isBlank() || own.given().isBlank()) return null;
            boolean jp = own.japanese();
            for (Name x : others) {
                if (x == own || x.family().isBlank() || x.given().isBlank()) continue;
                if (!FamilyForms.script(x.family()).equals(FamilyForms.script(own.family())) || FamilyForms.sameForm(x.family(), own.family(), jp) || !FamilyForms.sameForm(x.given(), own.given(), jp)) continue;
                for (String t : x.texts()) {
                    List<String> theirs = words(t);
                    if (theirs.size() != 2 || !FamilyForms.script(t).equals(FamilyForms.script(n))) continue;
                    for (int i = 0; i < 2; i++) {
                        String shared = mine.get(1 - i), differs = mine.get(i);
                        if (theirs.stream().anyMatch(w -> FamilyForms.sameForm(w, shared, jp)) && theirs.stream().noneMatch(w -> FamilyForms.sameForm(w, differs, jp)))
                            return new String[]{differs, shared};
                    }
                }
            }
            return null;
        }

        private String[] split(String id, String n) {
            String sc = FamilyForms.script(n);
            if (sc.equals("han") || sc.equals("kana")) {
                String joined = n.replaceAll("[\\s　・]+", "");
                String best = "";
                List<String> known = new ArrayList<>(familyParts);
                for (String k : known) {
                    String kj = k.replaceAll("[\\s　・]+", "");
                    if (kj.isEmpty() || kj.length() >= joined.length()) continue;
                    boolean begins = sc.equals("han") ? FamilyForms.hanKey(joined).startsWith(FamilyForms.hanKey(kj)) : FamilyForms.kanaKey(joined).startsWith(FamilyForms.kanaKey(kj));
                    if (begins && kj.length() > best.length()) best = kj;
                }
                if (best.isEmpty() && sc.equals("han")) for (String k : guessed()) {
                    if (FamilyForms.script(k).equals("han") && k.length() < joined.length() && FamilyForms.hanKey(joined).startsWith(FamilyForms.hanKey(k)) && k.length() > best.length()) best = k;
                }
                // two words with a space between them: the one the library knows as a family part, in whichever order the source wrote them (an
                // older Geni read wrote "勇 森田"); a space alone does not say which word is the family's
                if (n.strip().matches("\\S+[\\s　]+\\S+")) {
                    String[] w = n.strip().split("[\\s　]+");
                    boolean first = knownFamily(w[0]), second = knownFamily(w[1]);
                    if (first != second) return first ? new String[]{w[0], w[1]} : new String[]{w[1], w[0]};
                    // a reading in kana is written in the order of the name it reads, family name first
                    if (!first && sc.equals("kana")) return new String[]{w[0], w[1]};
                    if (best.isEmpty() || !best.equals(w[0])) return new String[]{"", n};
                }
                return best.isEmpty() ? new String[]{"", n} : new String[]{joined.substring(0, best.length()), joined.substring(best.length())};
            }
            if (sc.equals("latin") || sc.isEmpty()) {
                List<String> w = words(n);
                if (w.size() < 2 || n.contains(",")) return westernSplit(id, n);
                // the word the library knows as a family name
                List<String> known = w.stream().filter(this::knownFamilyWord).toList();
                if (known.size() == 1) {
                    List<String> given = new ArrayList<>(w); given.remove(known.get(0));
                    return new String[]{known.get(0), String.join(" ", given)};
                }
                // the romaji of a Japanese name is written in either order: which word is the family's is not known from the order
                if (japanesePerson(id)) return new String[]{"", n};
                return westernSplit(id, n);
            }
            return new String[]{"", n};
        }

        private String[] westernSplit(String id, String n) {
            Graph.Node like = new Graph.Node(id == null ? "" : id, "person", n, List.of(), "", 0);
            String[] gf = FamilyQuestions.givenFamily(g, like);
            return gf != null ? new String[]{gf[1], gf[0]} : new String[]{"", n};
        }

        /** Whether a word in characters is a family part the library knows: a claim's, a family's, or one it guesses from its people. */
        private boolean knownFamily(String w) {
            for (String p : familyParts) if (FamilyForms.sameForm(p, w)) return true;
            for (String p : guessed()) if (FamilyForms.sameForm(p, w)) return true;
            return false;
        }

        /** Whether a person has a name in Japanese characters or kana: their label, another name, or a name a claim gives. */
        private boolean japanesePerson(String id) {
            if (id == null || id.isEmpty()) return false;
            Graph.Node node = g.node(id);
            if (node != null) {
                if (FamilyForms.japanese(node.label())) return true;
                for (String a : node.aliases()) if (FamilyForms.japanese(a)) return true;
            }
            for (Finding f : nameClaims.getOrDefault(id, List.of())) {
                if (FamilyForms.japanese(written(f))) return true;
                for (Form x : formsOf(FamilyDetail.get(f, "forms"))) if (FamilyForms.japanese(x.text())) return true;
            }
            return false;
        }

        /** Every family name the library knows or guesses from its people ({@link FamilyFolder#everyFamilyName}), asked once and only when needed. */
        private List<String> guessed() {
            List<String> have = guessed;
            if (have != null) return have;
            List<String> out;
            try { out = FamilyFolder.everyFamilyName(g); } catch (RuntimeException e) { out = List.of(); }
            guessed = out;
            return out;
        }

        // ── the names the claims give ──

        /**
         * A person's names as the claims give them, and the label and other names no claim gives: each a form of the name it belongs to, or an
         * implicit name of its own. Nothing worked out yet, nothing ordered.
         */
        private List<Name> given(String id) {
            Graph.Node node = g.node(id);
            if (node == null) return List.of();
            List<Said> said = new ArrayList<>();
            List<Address> addresses = new ArrayList<>();
            addressed.put(id, addresses);
            // a claim that gives no name of the person (a farm's name, "Mrs. Tom Hale" for Tom's wife) is left out, with the other name it wrote
            Set<String> notNames = new HashSet<>();
            List<Said> all = new ArrayList<>();
            for (Finding f : nameClaims.getOrDefault(id, List.of())) {
                if (notAName(id, f)) { notNames.add(Vocabulary.norm(written(f))); continue; }
                all.add(read(id, f));
            }
            // everything the person is written as, for whether an initial with the family name abbreviates one of their names or several
            List<String> written = new ArrayList<>(List.of(node.label()));
            written.addAll(node.aliases());
            for (Said s : all) { written.add(s.written()); for (Form x : s.forms()) written.add(x.text()); }
            // how a text addresses the person is no name of theirs: a title with the family name alone, or an initial with it that says nothing
            // more and abbreviates none of their names, or several
            for (Said s : all) {
                List<String> texts = new ArrayList<>(List.of(s.written()));
                for (Form x : s.forms()) texts.add(x.text());
                boolean address = texts.stream().allMatch(t -> addressForm(id, t)) || s.kind().equals("unknown") && s.from() == null
                        && texts.stream().allMatch(t -> addressForm(id, t) || FamilyNames.initials(withoutNamesake(t)) && !abbreviatesOne(t, written));
                if (!address) { said.add(s); continue; }
                for (String t : texts) address(addresses, t, s.f().id());
            }
            said.sort(Comparator.comparingInt(Said::rank));
            // a claim in kana alone, on a person filed in characters, gives a reading of one of their names, never a name of its own: it goes
            // with the name in characters it stands beside in its words, or, where no claim gives one, with the name the entry is filed under
            Map<String, Said> readings = new LinkedHashMap<>();
            if (FamilyForms.script(node.label()).equals("han")) {
                List<Said> kept = new ArrayList<>();
                for (Said s : said) {
                    if (!readingOnly(s)) { kept.add(s); continue; }
                    Said of = readOf(s, said);
                    if (of != null) kept.add(readAs(s, of)); else readings.put(s.written(), s);
                }
                said = kept;
            }
            // claims of one name: the same parts, or a form in common. A name taken back is the same name carried again, and a period of its own
            List<List<Said>> groups = new ArrayList<>();
            for (Said s : said) {
                List<Said> into = null;
                for (List<Said> grp : groups) if (sameName(grp, s)) { into = grp; break; }
                if (into == null) groups.add(into = new ArrayList<>());
                into.add(s);
            }
            List<Name> out = new ArrayList<>();
            for (List<Said> grp : groups) out.add(joined(grp));
            // a name written with a title, in the index form or as a family name alone is a form of the name it stands for
            out = folded(id, out);
            // a spelling in Latin letters or kana that reads as a name in characters of the person is a way of writing that name
            out = readAsForms(id, out);
            // the label and the other names no claim gives: forms of the name they belong to, or implicit names of their own. The other names
            // in characters come first, then kana, then letters: the names in characters say which names there are, and a reading or a
            // romanised form is placed beside the one it reads
            List<String> loose = new ArrayList<>(List.of(node.label()));
            // a person known only by how another is related to them, joined in by the family's answer, is no name of theirs (as in joinedIn)
            List<String> others = new ArrayList<>(node.aliases().stream().filter(a -> a != null && !FamilyQuestions.placeholder(a) && !notNames.contains(Vocabulary.norm(a))).toList());
            for (String a : joinedIn(id)) if (!others.contains(a)) others.add(a);
            for (String sc : List.of("han", "kana", "latin")) for (String a : others) if (!loose.contains(a) && FamilyForms.script(a).equals(sc)) loose.add(a);
            for (String a : others) if (!loose.contains(a)) loose.add(a);
            int claimed = out.size();
            Name labelName = null;
            for (Name n : out) if (n.isForm(node.label())) labelName = n;
            for (String loose1 : loose) {
                boolean isLabel = loose1.equals(node.label());
                // an index's relation note after a name is no part of it; the entry's own name stays as it is filed
                String form = isLabel ? loose1 : FamilyNames.withoutRelationNote(loose1);
                // how a text addresses the person is no name of theirs, and is kept apart for showing; the entry's own name always stays
                if (!isLabel && (addressForm(id, form) || FamilyNames.initials(withoutNamesake(form)) && !abbreviatesOne(form, written))) { address(addresses, form, null); continue; }
                // a title and an index form's comma are no part of the name: Mr. Endo is compared as Endo, "Endo, Kenji" as Endo Kenji, and a
                // title or a family name alone goes to the one name it writes a part of
                String cmp = FamilyNames.comparable(form);
                int home = !cmp.equals(form.strip()) || aFamilyWord(id, cmp, out) ? home(id, cmp, out, -1) : -1;
                // a name the claims give with a mark this form lacks (初代 森田勇, Tom Hart Jr.) is the name this form writes
                if (home < 0) home = sameComparable(cmp, out);
                if (home >= 0) {
                    Name was = out.get(home), now = withForm(was, form);
                    out.set(home, now);
                    if (was == labelName || isLabel) labelName = now;
                    continue;
                }
                String[] ff = familyFirst(id, cmp, out);
                int at = place(id, out, claimed, labelName, ff == null ? cmp : ff[0] + ff[1], isLabel);
                if (at >= 0) {
                    Name was = out.get(at), now = withForm(was, form);
                    out.set(at, now);
                    if (was == labelName) labelName = now;
                    continue;
                }
                String[] ix = FamilyNames.indexForm(FamilyNames.untitled(form));
                String[] p = ff != null ? ff : ix != null ? ix : split(id, cmp, out);
                Name made = new Name(form, p[0], p[0].isEmpty() ? "" : p[1], List.of(), "unknown", "", null, false, null, "", List.of(), Evidence.clue, false, true, false, "");
                out.add(made);
                if (isLabel && labelName == null) labelName = made;
            }
            // a reading in kana no name in characters was claimed for goes with the name the entry is filed under, with its claim
            for (Said r : readings.values()) {
                int at = -1;
                for (int i = 0; i < out.size() && at < 0; i++) if (out.get(i).isForm(node.label())) at = i;
                if (at < 0) continue;
                Name was = out.get(at), now = withForm(was, r.written());
                List<String> claims = new ArrayList<>(now.claims());
                if (!claims.contains(r.f().id())) claims.add(r.f().id());
                out.set(at, new Name(now.written(), now.family(), now.given(), now.forms(), now.kind(), now.said(), now.from(), now.fromEvent(), now.to(), now.event(), claims, now.evidence(), now.accepted(), now.implicit(), now.workedOut(), now.basis()));
                if (was == labelName) labelName = out.get(at);
            }
            // a given name alone that is the given part of another of the person's names is a form of that name too: "Kenji" beside "Kenji
            // Morita", from a claim or from the list of names
            out = givenAlone(out);
            // a name written with a name carried beside the others and the person's own names is a form of it: "Paul Morita" beside "my
            // Christian name is Paul" and the name Kenji Morita
            return withBeside(out);
        }

        /**
         * A name in characters of two words with a space between, family part first, {family, given}: the word the library knows as a family
         * part, or else the word that is not the given part another name of the person ends with ("健二 遠藤", as an older Geni read wrote it
         * beside 森田健二, is 遠藤 and 健二). Null for any other form, and where neither says.
         */
        private String[] familyFirst(String id, String form, List<Name> names) {
            String f = form.strip();
            if (!FamilyForms.script(f).equals("han") || !f.matches("\\S+[\\s　]+\\S+")) return null;
            String[] p = split(id, f);
            if (!p[0].isEmpty()) return p;
            String[] w = f.split("[\\s　]+");
            return endsAName(names, w[0]) && !endsAName(names, w[1]) ? new String[]{w[1], w[0]} : null;
        }

        /** Whether a name of the person in characters ends with this word after something before it: the word is its given part. */
        private static boolean endsAName(List<Name> names, String word) {
            String k = FamilyForms.hanKey(word);
            for (Name n : names) for (String t : n.texts()) if (FamilyForms.script(t).equals("han") && FamilyForms.hanKey(t).length() > k.length() && FamilyForms.hanKey(t).endsWith(k)) return true;
            return false;
        }

        /**
         * The other names of the entries the owner joined into a person (merges.tsv): each such entry's own name and the other names it had,
         * a reading a source gave among them. A join writes only the entry's own name beside the person's; the rest stay under the entry.
         */
        private List<String> joinedIn(String id) {
            List<String> out = new ArrayList<>();
            for (String from : joined.getOrDefault(id, List.of())) {
                Vocabulary.Term t = g.curated().get(from);
                if (t == null) continue;
                List<String> names = new ArrayList<>(List.of(Graph.labelOf(t.description())));
                names.addAll(t.also());
                // a person known only by how another is related to them ("森田健二's father (written only as Endo)") is no name of theirs
                for (String a : names) if (a != null && !a.isBlank() && !FamilyQuestions.placeholder(a) && !out.contains(a.strip())) out.add(a.strip());
            }
            return out;
        }

        /**
         * Where a name no claim gives belongs among the person's names, by index; -1 for a name of its own. A form of a name is that name's.
         * Else the name the evidence ties it to: a name in characters to the implicit name, or the label's, of the same family part; a reading
         * or a romanised form to the name whose family part it reads, as a source reads it (a family's romanised name, a claim's kana), or
         * to the name whose romanised forms it matches but for an initial. What nothing ties goes where it alone can go: a reading of the
         * label to the label's name, as the reader writes it; a romanised form to the one name that nothing rules out, and, where two could
         * have it (遠藤健二 and 森田健二 for Kenji Endo), to a name of its own, so a form is never written under a name it may not be. A given
         * name alone (健二, Mary), or a name in letters with a word more or less (Mary Ann Ellis beside Mary Ellis), is a way of writing a name
         * of the person, never a name of its own: the label's name when it is one it could write, else the first.
         */
        private int place(String id, List<Name> out, int claimed, Name labelName, String form, boolean isLabel) {
            for (int i = 0; i < out.size(); i++) if (out.get(i).isForm(form)) return i;
            String sc = FamilyForms.script(form);
            int[] fit = new int[out.size()];
            for (int i = 0; i < out.size(); i++) fit[i] = fit(id, out.get(i), form, out);
            for (int i = 0; i < out.size(); i++) {
                if (fit[i] != 1) continue;
                // a name in characters joins only an implicit name or the label's: a claimed name keeps the forms its sources give
                if (sc.equals("han") && i < claimed && out.get(i) != labelName) continue;
                return i;
            }
            if (isLabel) return -1;
            int labelAt = labelName == null ? -1 : out.indexOf(labelName);
            List<Integer> part = new ArrayList<>();
            for (int i = 0; i < out.size(); i++) if (fit[i] == PARTIAL) part.add(i);
            if (!part.isEmpty()) return part.contains(labelAt) ? labelAt : part.get(0);
            List<Integer> open = new ArrayList<>();
            for (int i = 0; i < out.size(); i++) if (fit[i] == 0) open.add(i);
            // a name in characters or a reading in kana that nothing ties or rules out is the label's, as the reader writes the label's
            // characters and its reading beside it (Morita Isamu and 森田勇, 森田健二 and もりた けんじ)
            if ((sc.equals("han") || sc.equals("kana")) && labelAt >= 0 && open.contains(labelAt)) return labelAt;
            if (sc.equals("han")) return -1;
            return open.size() == 1 ? open.get(0) : -1;
        }

        /**
         * How a form no claim gives fits a name: 1 when the evidence ties it to the name, -1 when the evidence says it is another name's,
         * 0 when nothing says, {@link #PARTIAL} when it writes the name with a word more or less or is its given part alone. {@code names} are
         * the person's names so far: a reading whose given part is read another way is another name's only when another of them has the same
         * family part, since one name in characters may be read two ways (正二, しょうじ or まさじ).
         */
        private int fit(String id, Name n, String form, List<Name> names) {
            String sc = FamilyForms.script(form);
            if (sc.equals("han") && givenAlone(id, n, form)) return PARTIAL;
            boolean forIt = false, against = false, part = false;
            boolean jp = n.japanese() || FamilyForms.japanese(form) || japanesePerson(id);
            for (String written : n.texts()) {
                // the bracket that tells two entries of one name apart ("Ann Hale (born 1941)") is no part of the name it is compared as
                String t = withoutNamesake(written);
                String st = FamilyForms.script(t);
                if (!st.equals(sc)) continue;
                int r;
                if (sc.equals("han")) r = FamilyNames.surnames(List.of(t, form)).isEmpty() ? 1 : -1;
                else if (sc.equals("kana")) { r = kanaFit(t, form); if (r == GIVEN_READ_OTHERWISE) r = anotherOfItsFamily(id, n, names) ? -1 : 0; }
                else {
                    r = lettersFit(t, form, jp);
                    if (r == 0) { int p = partial(n, t, form, jp); if (p == PARTIAL) part = true; else r = p; }
                }
                if (r > 0) forIt = true; else if (r < 0) against = true;
            }
            if (!sc.equals("han")) {
                if (readsAs(id, n, form)) return 1;
                int r = readingFit(id, n, form);
                if (r > 0) forIt = true; else if (r < 0) against = true;
            }
            return against ? -1 : forIt ? 1 : part ? PARTIAL : 0;
        }

        /** {@link #fit}: the form writes the name with a word more or less, or is its given part alone. */
        private static final int PARTIAL = 3;

        /** Whether a form in characters is a name's given part alone: 健二 beside 森田健二, also where the name is written Kenji Morita with 森田健二 among its forms. */
        private boolean givenAlone(String id, Name n, String form) {
            List<String> givens = new ArrayList<>(List.of(n.given())), written = new ArrayList<>();
            if (n.given().isBlank() && n.family().isBlank()) written.add(n.written());
            for (Form f : n.forms()) if (FamilyForms.script(f.text()).equals("han")) written.add(f.text());
            for (String w : written) { String[] p = split(id, withoutNamesake(w)); if (!p[0].isEmpty()) givens.add(p[1]); }
            return givens.stream().anyMatch(g -> !g.isBlank() && FamilyForms.script(g).equals("han") && FamilyForms.hanKey(g).equals(FamilyForms.hanKey(form)));
        }

        /**
         * How a form in letters of another number of words fits a name's form in letters: every word of the shorter is a word of the longer.
         * A form with words left out keeps the given part (Mary for Mary Ellis; Ellis alone is no way of writing her name); a form with words
         * more adds a middle name to a name that has its family part (Mary Ann Ellis), or the family part it lacks (Morita Shōji for Shōji of
         * the Morita family), and another family's word makes it another name (Takahashi Shōji, or Mary Ann Hart beside Mary Hale), -1, where
         * the name's own family part is in letters. 0 when the words do not say.
         */
        private int partial(Name n, String t, String form, boolean romaji) {
            List<String> a = words(t), b = words(form);
            if (a.size() == b.size() || a.isEmpty() || b.isEmpty()) return 0;
            String fam = n.family(), given = n.given();
            boolean parts = FamilyForms.script(fam).equals("latin") && FamilyForms.script(given).equals("latin");
            List<String> shorter = a.size() < b.size() ? a : b, left = new ArrayList<>(a.size() < b.size() ? b : a);
            for (String w : shorter) {
                int at = -1;
                for (int i = 0; i < left.size() && at < 0; i++) if (FamilyForms.sameForm(left.get(i), w, romaji)) at = i;
                if (at < 0) {
                    // the words part: a family the library knows, written where the name's own family part is not, is another name
                    boolean hasOwn = parts && b.stream().anyMatch(x -> FamilyForms.sameForm(x, fam, romaji));
                    return parts && !hasOwn && b.stream().anyMatch(x -> !FamilyForms.sameForm(x, fam, romaji) && knownFamilyWord(x)) ? -1 : 0;
                }
                left.remove(at);
            }
            if (b.size() < a.size()) {
                if (parts) { for (String g : words(given)) if (left.stream().anyMatch(w -> FamilyForms.sameForm(w, g, romaji))) return 0; }
                else if (b.size() == 1 && knownFamilyWord(b.get(0))) return 0;
                return PARTIAL;
            }
            if (parts) {
                if (shorter.stream().anyMatch(w -> FamilyForms.sameForm(w, fam, romaji))) return PARTIAL;
                for (String w : left) if (!FamilyForms.sameForm(w, fam, romaji)) return -1;
                return PARTIAL;
            }
            // a family part in characters: a family word more may be its reading or another family's, which the letters do not say
            for (String w : left) if (knownFamilyWord(w)) return 0;
            return PARTIAL;
        }

        /**
         * How two forms in letters of one person fit: the same words, or one name written once with an initial (M. Ellis, Mary Ellis), is 1;
         * forms of as many words that differ in a whole word are two names, -1, whichever word it is (Mary Hale and Mary Ellis, Tom Hart and
         * John Hart, Kenji Endo and Kenji Morita); forms of another length say nothing. {@code romaji}: the words are compared as romaji.
         */
        private static int lettersFit(String t, String form, boolean romaji) {
            if (initialFits(t, form)) return 1;
            List<String> a = words(t), b = words(form);
            if (a.size() != b.size() || a.size() < 2) return 0;
            List<String> left = new ArrayList<>(b);
            for (String w : a) for (int i = 0; i < left.size(); i++) if (FamilyForms.sameForm(left.get(i), w, romaji)) { left.remove(i); break; }
            return left.isEmpty() ? 1 : -1;
        }

        /**
         * How two readings in kana of one person fit: written in words, the same words are 1, and a family part read otherwise is -1 (もりた
         * けんじ and えんどう けんじ are two names); the same family part with the given part read otherwise is {@link #GIVEN_READ_OTHERWISE}.
         * A reading is written family part first. Run together, one given part after two family parts is -1, as for names in characters.
         */
        private static int kanaFit(String t, String form) {
            String[] a = t.strip().split("[\\s　・]+"), b = form.strip().split("[\\s　・]+");
            if (a.length >= 2 && a.length == b.length) {
                List<String> left = new ArrayList<>();
                for (String w : b) left.add(FamilyForms.kanaKey(w));
                for (String w : a) left.remove(FamilyForms.kanaKey(w));
                if (left.isEmpty()) return 1;
                return FamilyForms.kanaKey(a[0]).equals(FamilyForms.kanaKey(b[0])) ? GIVEN_READ_OTHERWISE : -1;
            }
            return FamilyNames.surnames(List.of(t, form)).isEmpty() ? 0 : -1;
        }

        /** A name as written without the bracket after it that tells two entries of one name apart: "Ann Hale (born 1941)" is Ann Hale. */
        private static String withoutNamesake(String written) {
            String s = written == null ? "" : written.strip();
            String b = NAMESAKE.matcher(s).replaceAll("").strip();
            return b.isEmpty() ? s : b;
        }

        private static final Pattern NAMESAKE = Pattern.compile("\\s*[(（][^)）]*[)）]\\s*$");

        /** {@link #kanaFit}: the family part is read the same, the given part otherwise. */
        private static final int GIVEN_READ_OTHERWISE = 2;

        /** Whether another of the person's names has the family part of this one, so that the given part tells the two apart. */
        private boolean anotherOfItsFamily(String id, Name n, List<Name> names) {
            String fam = familyOf(id, n);
            if (fam.isBlank()) return true;
            for (Name o : names) if (o != n && sameFamily(fam, familyOf(id, o), n.japanese() || o.japanese())) return true;
            return false;
        }

        /**
         * How a reading or a romanised form fits a name through the readings a source gave its family part in characters: 1 when a word of
         * it reads the family part, -1 when the readings are known and no word of a form of two words or more reads it, 0 otherwise.
         */
        private int readingFit(String id, Name n, String form) {
            String fam = familyOf(id, n);
            if (!FamilyForms.script(fam).equals("han")) return 0;
            Set<String> r = new LinkedHashSet<>(readingsOf(fam));
            for (Form x : n.forms()) {
                if (!FamilyForms.script(x.text()).equals("kana") || !FamilyForms.hanKey(n.written()).startsWith(FamilyForms.hanKey(fam))) continue;
                String[] ws = x.text().strip().split("[\\s　・]+");
                if (ws.length >= 2) r.add(readingKey(ws[0]));
            }
            if (r.isEmpty()) return 0;
            List<String> ws = FamilyForms.script(form).equals("kana") ? List.of(form.strip().split("[\\s　・]+")) : words(form);
            for (String w : ws) if (r.contains(readingKey(w))) return 1;
            if (FamilyForms.script(form).equals("kana") && ws.size() == 1) {
                String whole = readingKey(form).replace(" ", "");
                for (String k : r) if (!k.isEmpty() && whole.startsWith(k.replace(" ", ""))) return 1;
                return 0;
            }
            return ws.size() >= 2 ? -1 : 0;
        }

        // ── titles, index forms and a family name alone: forms of the name they stand for ──

        /**
         * The names of the claims, each written with a title ("Mr. Endo", 遠藤さん), in the index form ("Endo, Kenji"), with Jr. or the numeral
         * of a hereditary name (初代), or as a family name alone joined into the one name it writes: its forms and its claims become that name's. A title is not part of the name, and neither
         * form is a name of its own. One that writes no name of the person, or more than one, stays where it is.
         */
        private List<Name> folded(String id, List<Name> ns) {
            List<Name> out = new ArrayList<>(ns);
            for (int i = 0; i < out.size(); i++) {
                Name n = out.get(i);
                String b = withoutNamesake(n.written()), c = FamilyNames.comparable(b);
                boolean aForm = allTitled(n) || !c.equals(b) || aFamilyWord(id, c, out);
                if (!aForm) continue;
                int home = home(id, c, out, i);
                if (home < 0) continue;
                out.set(home, absorbed(out.get(home), n));
                out.remove(i);
                i = -1;   // from the start again: the name it went into may now take another
            }
            return out;
        }

        /**
         * A given name alone, folded into another name of the person whose given part it is, as a form of that name: "Kenji" beside "Kenji
         * Morita" is how a text writes him short, not a name of his own, and not his name at birth, even where the reader filed it as the name
         * at birth. A name carried beside the others ("my Christian name is Paul") stays a name of its own; so does one that no other name has
         * as its given part.
         */
        private static List<Name> givenAlone(List<Name> ns) {
            List<Name> out = new ArrayList<>(ns);
            for (int i = 0; i < out.size(); i++) {
                Name n = out.get(i);
                // a name a source calls the one at birth is still only how it writes the whole name short; one carried beside the others is its own
                if (!(n.kind().equals("unknown") || n.kind().equals("birth"))) continue;
                boolean onePart = n.family().isBlank() || n.given().isBlank();
                String w = FamilyNames.comparable(withoutNamesake(n.written())).strip();
                String sc = FamilyForms.script(w);
                if (w.isEmpty() || !sc.equals("latin") && w.matches(".*[\\s　・].*")) continue;
                // the name whose whole given part it is ("Mary Haru" of "Morita Mary Haru"); else, a word alone, the one whose given part has it
                int home = -1, part = -1;
                for (int k = 0; k < out.size(); k++) {
                    Name m = out.get(k);
                    if (k == i || m.family().isBlank() || m.given().isBlank() || ALONGSIDE.contains(m.kind())) continue;
                    // a family part a source gives the short name is its own ("Shōji", born into the Morita family, is no form of Takahashi Shōji)
                    if (!n.family().isBlank() && !n.implicit() && !FamilyForms.sameForm(n.family(), m.family(), true)) continue;
                    if (home < 0 && FamilyForms.sameForm(m.given(), w, true)) home = k;
                    if (part < 0 && onePart && sc.equals("latin") && words(w).size() == 1)
                        for (String x : words(FamilyNames.comparable(m.given()))) if (FamilyForms.sameForm(x, w, true)) part = k;
                }
                if (home < 0) home = part;
                if (home < 0) continue;
                // the name it goes into keeps its own kind: the short form only adds a way of writing it
                Name into = out.get(home);
                Name merged = absorbed(into, n);
                out.set(home, new Name(merged.written(), merged.family(), merged.given(), merged.forms(), into.kind(), into.said(), into.from(), into.fromEvent(), into.to(), into.event(),
                        merged.claims(), merged.evidence(), merged.accepted(), merged.implicit(), merged.workedOut(), merged.basis(), merged.familyReadings()));
                out.remove(i);
                i = -1;
            }
            return out;
        }

        /**
         * The names, each written in Latin letters with a name carried beside the others (a religious, art or other name) and with words of
         * the person's other names, folded into that name as a form of it: "Paul Morita" and "Paul Kenji Morita" beside the Christian name
         * Paul and the name Kenji Morita. Only a name whose kind is not known is folded; the words must all be the person's own.
         */
        private static List<Name> withBeside(List<Name> ns) {
            List<Name> out = new ArrayList<>(ns);
            for (boolean again = true; again; ) {
                again = false;
                for (int i = 0; i < out.size() && !again; i++) {
                    Name m = out.get(i);
                    if (m.replaces() || !FamilyForms.script(m.written()).equals("latin")) continue;
                    List<String> mw = words(FamilyNames.comparable(withoutNamesake(m.written())));
                    for (int k = 0; k < out.size() && !again; k++) {
                        Name n = out.get(k);
                        if (k == i || !n.kind().equals("unknown") || !FamilyForms.script(n.written()).equals("latin")) continue;
                        List<String> left = new ArrayList<>(words(FamilyNames.comparable(withoutNamesake(n.written())).replace(",", " ")));
                        if (left.size() <= mw.size()) continue;
                        boolean all = true;
                        for (String w : mw) { int at = -1; for (int x = 0; x < left.size() && at < 0; x++) if (FamilyForms.sameForm(left.get(x), w, true)) at = x; if (at < 0) { all = false; break; } left.remove(at); }
                        if (!all || !left.stream().allMatch(w -> carried(out, m, n, w))) continue;
                        out.set(i, absorbed(m, n));
                        out.remove(k);
                        again = true;
                    }
                }
            }
            return out;
        }

        /** Whether a word is one of another of the person's names, other than {@code a} and {@code b}: a word of it, or its family or given part. */
        private static boolean carried(List<Name> ns, Name a, Name b, String w) {
            for (Name o : ns) {
                if (o == a || o == b) continue;
                if (FamilyForms.sameForm(o.family(), w, true) || FamilyForms.sameForm(o.given(), w, true)) return true;
                for (String t : o.texts()) for (String x : words(FamilyNames.comparable(withoutNamesake(t)).replace(",", " "))) if (FamilyForms.sameForm(x, w, true)) return true;
            }
            return false;
        }

        /** Whether every way a name is written carries a title or an honorific ({@link FamilyNames#titled}): a form of a name, never a name of its own. */
        private static boolean allTitled(Name n) {
            for (String t : n.texts()) if (!FamilyNames.titled(withoutNamesake(t))) return false;
            return true;
        }

        /**
         * Whether a word alone is a family part: one the library knows as one (a claim's, a family's, a reading of one), or the family part of
         * another of the person's names. A family name alone written as another name is a form of the name it is the family part of, for
         * showing only.
         */
        private boolean aFamilyWord(String id, String word, List<Name> names) {
            String w = word == null ? "" : word.strip();
            if (w.isEmpty() || w.matches(".*[\\s　・,].*")) return false;
            String sc = FamilyForms.script(w);
            if (sc.equals("latin") && knownFamilyWord(w)) return true;
            for (String p : familyParts) if (FamilyForms.script(p).equals(sc) && FamilyForms.sameForm(p, w)) return true;
            for (Name m : names) if (!m.family().isBlank() && !FamilyForms.sameForm(m.written(), w) && FamilyForms.sameForm(m.family(), w, m.japanese())) return true;
            return false;
        }

        /** The index of the one name that is this form once the marks that are no part of a name are taken off both ({@link FamilyNames#comparable}); -1 for none or several. */
        private static int sameComparable(String cmp, List<Name> out) {
            int found = -1;
            for (int i = 0; i < out.size(); i++) {
                boolean same = false;
                for (String t : out.get(i).texts()) if (FamilyForms.sameForm(FamilyNames.comparable(withoutNamesake(t)), cmp)) { same = true; break; }
                if (!same) continue;
                if (found >= 0) return -1;
                found = i;
            }
            return found;
        }

        /** The index of the one name ({@code self} left out) a form without its title writes; -1 when it writes none, or more than one. */
        private int home(String id, String cmp, List<Name> out, int self) {
            String c = withoutNamesake(cmp);
            if (c.isBlank()) return -1;
            int found = -1;
            for (int i = 0; i < out.size(); i++) {
                if (i == self || allTitled(out.get(i))) continue;
                if (!writes(id, out.get(i), c)) continue;
                if (found >= 0) return -1;
                found = i;
            }
            return found;
        }

        /**
         * Whether a form, its title taken off, writes a name: it is a form of it; or it is one word, the name's family part or given part or
         * a word of one of its forms in letters; or, in characters, the name's family part or given part alone.
         */
        private boolean writes(String id, Name m, String c) {
            if (m.isForm(c)) return true;
            boolean jp = m.japanese() || FamilyForms.japanese(c);
            String sc = FamilyForms.script(c);
            List<String> cw = words(c);
            if (sc.equals("latin")) {
                for (String t : m.texts()) {
                    String u = FamilyNames.comparable(withoutNamesake(t));
                    if (!FamilyForms.script(u).equals("latin")) continue;
                    if (cw.size() == 1) { if (words(u).size() >= 2) for (String x : words(u)) if (FamilyForms.sameForm(x, cw.get(0), jp)) return true; }
                    else if (lettersFit(u, c, jp) > 0) return true;
                }
                if (cw.size() == 1) return (!m.family().isBlank() && FamilyForms.sameForm(m.family(), c, jp)) || (!m.given().isBlank() && !m.family().isBlank() && FamilyForms.sameForm(m.given(), c, jp));
                return false;
            }
            if (sc.equals("han") || sc.equals("kana")) {
                if (c.matches(".*[\\s　・].*")) return false;
                if (!m.family().isBlank() && FamilyForms.sameForm(m.family(), c)) return true;
                if (!m.given().isBlank() && !m.family().isBlank() && FamilyForms.sameForm(m.given(), c)) return true;
                for (String t : m.texts()) {
                    String u = FamilyNames.untitled(withoutNamesake(t));
                    if (!FamilyForms.script(u).equals(sc)) continue;
                    if (FamilyForms.sameForm(u, c)) return true;
                    String[] p = split(id, u);
                    if (!p[0].isBlank() && (FamilyForms.sameForm(p[0], c) || FamilyForms.sameForm(p[1], c))) return true;
                }
            }
            return false;
        }

        private volatile Map<String, List<String>> cached;

        /** The readings the link pass kept in links.json, by the characters; read once, never asked for. */
        private Map<String, List<String>> cachedReadings() {
            Map<String, List<String>> c = cached;
            if (c == null) { try { c = FamilyLinks.cachedReadings(g.store()); } catch (RuntimeException e) { c = Map.of(); } cached = c; }
            return c;
        }

        /**
         * A name in Latin letters or kana that reads as a name in characters of the same person is a form of that name, shown as "also
         * written", with that name's kind and years; it is not a name of its own and raises no question. The reading: a reading in kana filed
         * with the name (L6), or the readings the link pass kept (L7), whole name or part by part; a title comes off first (L9). A spelling
         * that reads as no name of the person stays a name of its own.
         */
        private List<Name> readAsForms(String id, List<Name> ns) {
            List<Name> out = new ArrayList<>(ns);
            for (int i = 0; i < out.size(); i++) {
                Name n = out.get(i);
                if (n.texts().stream().anyMatch(t -> FamilyForms.script(FamilyNames.comparable(withoutNamesake(t))).equals("han"))) continue;
                int home = -1;
                for (int j = 0; j < out.size(); j++) {
                    if (j == i) continue;
                    Name m = out.get(j);
                    if (!FamilyForms.script(m.written()).equals("han") || n.texts().stream().noneMatch(t -> readsAs(id, m, t))) continue;
                    if (home >= 0) { home = -2; break; }   // two names in characters it could read as: the family says which
                    home = j;
                }
                if (home < 0) continue;
                out.set(home, absorbed(out.get(home), n));
                out.remove(i);
                i = -1;
            }
            return out;
        }

        /**
         * Whether a spelling in Latin letters or kana reads as a name in characters: its two words, the title taken off and an index's order
         * put right, read the name's family part and its given part, as a reading in kana filed with the name gives them or as the readings
         * the link pass kept give each part; or the whole spelling is a reading filed with the name.
         */
        boolean readsAs(String id, Name m, String spelling) {
            String c = FamilyNames.comparable(withoutNamesake(spelling));
            String sc = FamilyForms.script(c);
            if (!sc.equals("latin") && !sc.equals("kana")) return false;
            String[] ix = FamilyNames.indexForm(c);
            List<String> ws = sc.equals("kana") ? List.of(c.strip().split("[\\s　・]+")) : words(ix != null ? ix[0] + " " + ix[1] : c);
            if (ws.size() != 2) return false;
            String w0 = readingKey(ws.get(0)), w1 = readingKey(ws.get(1));
            if (w0.isEmpty() || w1.isEmpty()) return false;
            String fam = familyOf(id, m);
            String given = m.given().isBlank() ? split(id, m.written())[1] : m.given();
            if (!FamilyForms.script(fam).equals("han") || given.isBlank()) return false;
            Set<String> famKeys = new HashSet<>(readingsOf(fam)), givenKeys = new HashSet<>();
            for (Form x : m.forms()) {
                if (!FamilyForms.script(x.text()).equals("kana")) continue;
                String[] k = x.text().strip().split("[\\s　・]+");
                if (k.length == 2) { famKeys.add(readingKey(k[0])); givenKeys.add(readingKey(k[1])); }
            }
            for (String r : cachedReadings().getOrDefault(fam + FamilyLinks.FAMILY, List.of())) famKeys.add(readingKey(r));
            for (String r : cachedReadings().getOrDefault(given + FamilyLinks.GIVEN, List.of())) givenKeys.add(readingKey(r));
            return famKeys.contains(w0) && givenKeys.contains(w1) || famKeys.contains(w1) && givenKeys.contains(w0);
        }

        /** A name with the forms and the claims of a form of it ({@link #folded}): its parts taken from the form only where it has none and the form has both. */
        private static Name absorbed(Name m, Name n) {
            List<Form> forms = new ArrayList<>(m.forms());
            for (String t : n.texts()) if (!m.written().equals(t) && forms.stream().noneMatch(x -> x.text().equals(t))) forms.add(new Form(t, FamilyForms.lang(t, m.japanese() || FamilyForms.japanese(t))));
            List<String> claims = new ArrayList<>(m.claims());
            for (String c : n.claims()) if (!claims.contains(c)) claims.add(c);
            boolean parts = m.family().isBlank() && !n.family().isBlank() && !n.given().isBlank();
            Evidence ev = m.evidence() == null ? n.evidence() : n.evidence() == null || m.evidence().ordinal() <= n.evidence().ordinal() ? m.evidence() : n.evidence();
            return new Name(m.written(), parts ? n.family() : m.family(), parts ? n.given() : m.given(), forms, m.kind().equals("unknown") ? n.kind() : m.kind(), m.said().isBlank() ? n.said() : m.said(),
                    m.from() != null ? m.from() : n.from(), m.from() != null ? m.fromEvent() : n.fromEvent(), m.to() != null ? m.to() : n.to(), m.event().isBlank() ? n.event() : m.event(),
                    claims, ev, m.accepted() || n.accepted(), m.implicit() && n.implicit(), m.workedOut(), m.basis(), m.familyReadings());
        }

        /** How a text addresses a person, kept apart from their names: the words, and the claims that wrote them (none for an other name typed or read in). */
        public record Address(String text, List<String> claims) { }

        private final Map<String, List<Address>> addressed = new HashMap<>();

        /**
         * The forms of address a person is written with, which are no names of theirs ({@link #addressForm}): "Pastor Hart", "Hart-sensei",
         * "T. Hart" beside two names it could abbreviate, each once, with the claims that wrote it. Empty for a node the graph does not have.
         */
        public synchronized List<Address> addressedAs(String id) {
            names(id);
            return List.copyOf(addressed.getOrDefault(id, List.of()));
        }

        private static void address(List<Address> into, String text, String claim) {
            String t = text == null ? "" : text.strip();
            if (t.isEmpty()) return;
            for (int i = 0; i < into.size(); i++) {
                Address a = into.get(i);
                if (!Vocabulary.norm(a.text()).equals(Vocabulary.norm(t))) continue;
                if (claim != null && !a.claims().contains(claim)) { List<String> c = new ArrayList<>(a.claims()); c.add(claim); into.set(i, new Address(a.text(), c)); }
                return;
            }
            into.add(new Address(t, claim == null ? List.of() : List.of(claim)));
        }

        /**
         * Whether a written form is how a text addresses the person, and no name of theirs: a title or an honorific with the family name alone
         * ("Mr. Hart", "Pastor Hart", "Hart-sensei", 遠藤さん), by the words ({@link FamilyNames#formOfAddress}) or, in characters, by the family
         * parts the library knows. A title with a whole name is that name, written with the title.
         */
        boolean addressForm(String id, String text) {
            String b = withoutNamesake(text), u = FamilyNames.untitled(b);
            if (u.equals(b) || u.isEmpty()) return false;
            if (FamilyNames.formOfAddress(b)) return true;
            if (!FamilyForms.japanese(u) || u.matches(".*[\\s　・].*")) return false;
            return aFamilyWord(id, u, List.of()) || split(id, u)[0].isBlank() && FamilyForms.hanKey(u).codePointCount(0, FamilyForms.hanKey(u).length()) <= 2;
        }

        /**
         * Whether a name of a person is only a form of a name, never a name of its own, and so never a reason to ask how or when it came: every
         * way it is written is a title with one word (Mr. Endo, Viscount Endo, 遠藤さん), or a family name alone ({@link #aFamilyWord}), as an
         * older library kept "Endo" among a person's other names. A title with a whole name (Mrs. Ruth Hale) writes that whole name.
         */
        public boolean formOnly(String id, Name n) {
            if (n == null) return false;
            List<Name> others = names(id).stream().filter(x -> x != n).toList();
            for (String t : n.texts()) {
                String b = withoutNamesake(t), c = FamilyNames.comparable(b);
                boolean oneWord = FamilyForms.script(c).equals("latin") ? words(c).size() == 1 : aFamilyWord(id, c, others);
                // a title with one word (Mr. Endo, Viscount Endo, Prince Tom), or a family name alone: a part of a name, for showing only
                if (oneWord && (FamilyNames.titled(b) || aFamilyWord(id, c, others))) continue;
                return false;
            }
            return true;
        }

        /**
         * Whether a name is a full name, not one word: a family part and a given part the claims give; in letters, two words or more without a
         * title; in characters or kana, anything but a family name alone, since where one part ends is not written there. A title with one word
         * (Viscount Endo), a one-word name in letters (Ann, Tom) and a family name alone (遠藤) are not.
         */
        public boolean whole(String id, Name n) {
            if (n == null) return false;
            if (!n.family().isBlank() && !n.given().isBlank()) return true;
            String c = FamilyNames.comparable(withoutNamesake(n.written()));
            if (FamilyForms.script(c).equals("latin")) return words(c).size() >= 2;
            return !aFamilyWord(id, c, List.of());
        }

        /**
         * Of a person's full names, the latest: a dated one taken last, else the first in the order of the life; null when there is none. A
         * name written only with a title is left out: "Mrs. Tom Hale" may be written by her husband's name.
         */
        private Name wholeName(String id) {
            List<Name> ws = names(id).stream().filter(n -> whole(id, n) && !allTitled(n) && !formOnly(id, n)).toList();
            if (ws.isEmpty()) return null;
            List<Name> dated = ws.stream().filter(n -> n.from() != null).toList();
            if (!dated.isEmpty()) return dated.stream().max(Comparator.comparingInt((Name n) -> n.from().year()).thenComparing(n -> -rankOf(n))).orElseThrow();
            List<Name> later = ws.stream().filter(n -> n.replaces() && !n.kind().equals("birth")).toList();
            return later.isEmpty() ? ws.get(0) : later.get(0);
        }

        /**
         * Whether which word of a name in letters is its family part is not known: a name written like romanised Japanese ({@link
         * FamilyForms#romajiName}), whose order nothing in the library settles. What settles it: a claim that gives the name its parts, the
         * text's own "Family, Given", a family or a reading the library knows for one of its words, a relative's name that carries one of them,
         * or the person's other names. Morita Shoichi alone may be the Morita family's Shoichi or the Shoichi family's Morita.
         */
        public boolean orderUnknown(String id, String name) {
            String n = withoutNamesake(FamilyNames.untitled(name == null ? "" : name)).strip();
            if (!FamilyForms.romajiName(n)) return false;
            for (String w : words(n)) if (knownFamilyWord(w)) return false;
            Graph.Node node = id == null ? null : g.node(id);
            if (node != null) {
                List<Name> ns = names(id);
                for (Name x : ns) if (!x.implicit() && !x.family().isBlank() && x.isForm(n)) return false;
                if (byOtherNames(n, ns) != null) return false;
                if (FamilyQuestions.kinWord(g, node, n) != null) return false;
            }
            return true;
        }

        private static Name withForm(Name n, String form) {
            if (n.texts().contains(form)) return n;
            List<Form> forms = new ArrayList<>(n.forms());
            forms.add(new Form(form, FamilyForms.lang(form, n.japanese() || FamilyForms.japanese(form))));
            return new Name(n.written(), n.family(), n.given(), forms, n.kind(), n.said(), n.from(), n.fromEvent(), n.to(), n.event(), n.claims(), n.evidence(), n.accepted(), n.implicit(), n.workedOut(), n.basis());
        }

        /**
         * Whether a name claim gives no name of the person: its words name a thing ({@link #namesAThing}), or write a woman by her husband's
         * name, as the words themselves show it ({@link #addressedByHisName}) or as the name is her husband's.
         */
        private boolean notAName(String id, Finding f) {
            String q = FamilyChecks.quoteOf(f), w = written(f);
            if (namesAThing(q, w) || addressedByHisName(q, w)) return true;
            String his = mrsWith(w);
            if (his == null) return false;
            for (String[] sp : spouses.getOrDefault(id, List.of())) {
                Graph.Node s = g.node(sp[0]);
                if (s == null) continue;
                Set<String> theirs = new HashSet<>(FamilyNames.keys(s.label()));
                for (String a : s.aliases()) theirs.addAll(FamilyNames.keys(a));
                if (!Collections.disjoint(theirs, FamilyNames.keys(his))) return true;
            }
            return false;
        }

        /**
         * Whether every claim that gives a name is an index's cross-reference to it ({@link #crossReference}): the name is another form of the
         * person's, and nothing says how it came, so no question is asked about it.
         */
        public boolean crossReferenceOnly(Name n) {
            if (n == null || n.claims().isEmpty()) return false;
            for (String c : n.claims()) {
                Finding f = finding(c);
                String q = f == null ? "" : FamilyChecks.quoteOf(f);
                if (n.texts().stream().noneMatch(t -> crossReference(q, t))) return false;
            }
            return true;
        }

        /**
         * Whether a claim gives only a reading: its name and every form are kana alone, and no claim says it is a name of a kind of its own
         * (a name carried beside the others, such as a religious or an art name in kana, is a name).
         */
        private static boolean readingOnly(Said s) {
            if (!s.kind().equals("unknown") || !FamilyForms.script(s.written()).equals("kana")) return false;
            for (Form x : s.forms()) if (!FamilyForms.script(x.text()).equals("kana")) return false;
            return true;
        }

        /**
         * The claimed name in characters a reading stands beside in the words of its claim: the one whose name or given part the words write
         * nearest before the reading; the one name in characters when the words write none. Null when there is none, or several and the words
         * do not say which.
         */
        private static Said readOf(Said r, List<Said> said) {
            List<Said> han = said.stream().filter(x -> x != r && FamilyForms.script(x.written()).equals("han")).toList();
            if (han.isEmpty()) return null;
            String q = KanjiForms.modern(FamilyChecks.quoteOf(r.f()));
            int at = q.indexOf(KanjiForms.modern(r.written()));
            Said best = null;
            int nearest = -1;
            for (Said x : han) {
                List<String> texts = new ArrayList<>(List.of(x.written()));
                if (!x.given().isBlank()) texts.add(x.given());
                for (String t : texts) {
                    String k = KanjiForms.modern(t).replaceAll("[\\s　・]+", "");
                    int found = at < 0 ? q.lastIndexOf(k) : q.lastIndexOf(k, at);
                    if (found >= 0 && found > nearest) { nearest = found; best = x; }
                }
            }
            if (best != null) return best;
            return han.size() == 1 ? han.get(0) : null;
        }

        /**
         * A reading's claim as a claim of the name in characters it reads: the same written name and parts, the kana among its forms, and
         * nothing else said, so that it groups with that name's claims and adds only its words and its source.
         */
        private static Said readAs(Said r, Said of) {
            List<Form> forms = new ArrayList<>();
            forms.add(new Form(r.written(), FamilyForms.lang(r.written())));
            for (Form x : r.forms()) if (forms.stream().noneMatch(y -> y.text().equals(x.text()))) forms.add(x);
            return new Said(r.f(), of.written(), of.family(), of.given(), forms, "unknown", "", null, false, null, "", r.rank(), r.evidence(), r.accepted());
        }

        /** What one claim says of a name, and its rank: the family's word first, then a record, something published, a clue. */
        private Said read(String id, Finding f) {
            Map<String, String> d = FamilyDetail.of(f);
            // an older claim may still carry an index's relation note after the name: no part of it
            String written = FamilyNames.withoutRelationNote(FamilyNameHistory.written(f));
            String kind = d.containsKey("kind") ? kind(d.get("kind")) : kindFromWords(f.triple().predicate());
            List<Form> forms = new ArrayList<>();
            for (Form x : formsOf(d.getOrDefault("forms", ""))) { String t = FamilyNames.withoutRelationNote(x.text()); if (forms.stream().noneMatch(y -> y.text().equals(t))) forms.add(new Form(t, x.lang())); }
            // a title is no part of the name: a claim that writes a whole name with one ("Father Kenji Morita") gives the name Kenji Morita,
            // written so among its forms
            String bare = FamilyNames.untitled(written), titled = written;
            if (!bare.equals(titled) && !addressForm(id, titled) && !FamilyNames.initials(bare)) {
                if (forms.stream().noneMatch(y -> y.text().equals(titled))) forms.add(new Form(titled, FamilyForms.lang(titled)));
                written = bare;
            }
            String family = d.getOrDefault("family", ""), given = d.getOrDefault("given", "");
            // the text's own "Family, Given" says which part is which
            String[] ix = family.isBlank() && given.isBlank() ? FamilyNames.indexForm(written) : null;
            if (ix != null) { family = ix[0]; given = ix[1]; }
            String said = d.getOrDefault("said", "");
            // the text's own words, tied to the name, give the kind a reader left unknown: "my Christian name is Paul"
            if (kind.equals("unknown")) {
                String[] w = kindInWords(FamilyChecks.quoteOf(f), written, given);
                if (w != null) { kind = w[0]; said = w[1]; }
            }
            String from = d.getOrDefault("from", ""), to = d.getOrDefault("to", ""), event = d.getOrDefault("event", "");
            boolean fromEvent = from.equalsIgnoreCase("event");
            FamilyDate fromDate = fromEvent ? eventDate(event) : !from.isBlank() ? FamilyDate.parse(from) : null;
            if (fromDate == null && !fromEvent && from.isBlank()) fromDate = FamilyChecks.claimDate(f);
            if (fromDate == null && kind.equals("birth")) fromDate = born.get(id);
            FamilyDate toDate = to.equalsIgnoreCase("event") ? eventDate(d.getOrDefault("event-out", "")) : !to.isBlank() ? FamilyDate.parse(to) : null;
            boolean accepted = f.state() == Finding.State.accepted && f.review() != null && "person".equals(f.review().reviewer());
            Evidence ev = Evidence.of(f);
            int rank = accepted ? 0 : ev.ordinal() + 1;
            return new Said(f, written, family, given, forms, kind, said, fromDate, fromEvent, toDate, event, rank, ev, accepted);
        }

        private FamilyDate eventDate(String claim) {
            Finding e = finding(claim);
            return e == null ? null : FamilyChecks.claimDate(e);
        }

        private static boolean sameName(List<Said> grp, Said s) {
            Said first = grp.get(0);
            if (first.kind().equals("taken-back") != s.kind().equals("taken-back")) return false;
            for (Said x : grp) {
                boolean jp = x.japanese() || s.japanese();
                // the same parts in the same script are the same name; parts in two scripts are compared through the forms below
                if (!x.family().isBlank() && !s.family().isBlank() && !x.given().isBlank() && !s.given().isBlank() && FamilyForms.script(x.family()).equals(FamilyForms.script(s.family())))
                    return FamilyForms.sameForm(x.family(), s.family(), jp) && FamilyForms.sameForm(x.given(), s.given(), jp);
                if (x.written().equals(s.written()) || FamilyForms.sameForm(x.written(), s.written(), jp)) return true;
                // a title and an index form's comma are no part of the name: Mr. Endo and Viscount Endo are one form, Endo, Kenji and Endo Kenji another
                if (FamilyForms.sameForm(FamilyNames.comparable(x.written()), FamilyNames.comparable(s.written()), jp)) return true;
                for (Form a : x.forms()) for (Form b : s.forms()) if (FamilyForms.script(a.text()).equals(FamilyForms.script(b.text())) && FamilyForms.sameForm(a.text(), b.text(), jp)) return true;
            }
            return false;
        }

        /** The claims of one name as one name: each value from the best claim that gives one. */
        private static Name joined(List<Said> grp) {
            Said best = grp.get(0);
            String family = "", given = "", kind = "unknown", said = "", event = "";
            FamilyDate from = null, to = null;
            boolean fromEvent = false, accepted = false;
            List<Form> forms = new ArrayList<>();
            List<String> claims = new ArrayList<>();
            Evidence ev = Evidence.clue;
            for (Said s : grp) {
                if (family.isEmpty() && !s.family().isBlank()) { family = s.family(); given = s.given(); }
                if (kind.equals("unknown") && !s.kind().equals("unknown")) kind = s.kind();
                if (said.isEmpty() && !s.said().isBlank()) said = s.said();
                if (event.isEmpty() && !s.event().isBlank()) event = s.event();
                if (from == null && s.from() != null) { from = s.from(); fromEvent = s.fromEvent(); }
                if (to == null && s.to() != null) to = s.to();
                accepted |= s.accepted();
                if (s.evidence().ordinal() < ev.ordinal()) ev = s.evidence();
                claims.add(s.f().id());
                for (Form f : s.forms()) if (forms.stream().noneMatch(x -> x.text().equals(f.text())) && !f.text().equals(best.written())) forms.add(f);
                if (!s.written().equals(best.written()) && forms.stream().noneMatch(x -> x.text().equals(s.written()))) forms.add(new Form(s.written(), FamilyForms.lang(s.written(), best.japanese())));
            }
            return new Name(best.written(), family, given, forms, kind, said, from, fromEvent, to, event, claims, ev, accepted, false, false, "");
        }

        // ── what the evidence settles ──

        /**
         * The names whose kind or year the evidence settles, worked out and marked so; never filed. A name of the family part the husband or
         * wife carried AT the marriage was taken at that marriage, and came with it when nothing points to more; a name of the family a person
         * entered, as a claim says how (婿養子, an adoption), was taken on that entry; the first name, of a birth parent's family part, is the
         * name at birth. Where two rules
         * say two things of one name, or a rule fits two names, nothing is worked out: that is a question for the family.
         */
        private List<Name> explained(String id, List<Name> ns) {
            Map<Integer, List<Name>> proposed = new LinkedHashMap<>();
            Set<Integer> torn = new HashSet<>();   // the names the evidence says two things of
            byMarriage(id, ns, proposed, torn);
            byEntry(id, ns, proposed, torn);
            byEvent(id, ns, proposed, torn);
            // the name at birth is judged beside the other names as those rules settle them: a married name the marriage dates comes later
            List<Name> settled = settle(id, ns, proposed, torn);
            atBirth(id, ns, settled, proposed);
            // the words that say which of two names came first ("Yuri Hale (formerly Yuri Ellis)"), beside what the other rules settled
            byFormer(id, ns, settled, proposed);
            return settle(id, ns, proposed, torn);
        }

        /**
         * A name the words call the earlier one, beside a later name of the person: "X (formerly Y)", "X, formerly Y", "formerly known as
         * Y", 旧姓 Y. Where X is the name taken at a marriage (a claim says so, or the marriage settles it) and no other change of family
         * name is known, Y is the name at birth, worked out, carried up to the marriage. Where how X came is not known, Y is the earlier
         * name and X the later, and nothing is asked. Where X came another way, Y is the earlier name. {@code settled}: the names as the other
         * rules settle them.
         */
        private void byFormer(String id, List<Name> ns, List<Name> settled, Map<Integer, List<Name>> proposed) {
            for (int i = 0; i < ns.size(); i++) {
                Name y = ns.get(i);
                if (!y.kind().equals("unknown") || !y.replaces() || y.claims().isEmpty()) continue;
                int x = -1;
                for (String c : y.claims()) {
                    Finding f = finding(c);
                    String q = f == null ? "" : FamilyChecks.quoteOf(f);
                    for (String t : y.texts()) {
                        String head = formerName(q, t);
                        if (head == null || head.isEmpty()) continue;
                        x = laterNameIn(head, ns, i);
                        if (x >= 0) break;
                    }
                    if (x >= 0) break;
                }
                if (x < 0) continue;
                Name later = settled.get(x);
                String fam = familyOf(id, y), given = y.family().isBlank() ? split(id, y.written())[1] : y.given();
                boolean married = later.kind().equals("marriage");
                // no other change of family name is known: every replacing name is X's or Y's
                boolean otherChange = false;
                for (int k = 0; k < ns.size(); k++) {
                    Name o = settled.get(k);
                    if (k == i || k == x || !o.replaces()) continue;
                    String of = familyOf(id, o);
                    if (!of.isBlank() && !sameFamily(of, fam, y.japanese()) && !sameFamily(of, familyOf(id, later), o.japanese())) otherChange = true;
                }
                if (married && !otherChange) {
                    FamilyDate from = y.from() != null ? y.from() : born.get(id);
                    propose(proposed, i, new Name(y.written(), fam, given, y.forms(), "birth", y.said(), from, false, later.from() != null ? later.from() : y.to(), y.event(), y.claims(), y.evidence(), y.accepted(), y.implicit(), true, BASIS_FORMERLY));
                } else {
                    propose(proposed, i, worked(y, fam, given, "earlier", y.from(), y.event(), BASIS_FORMERLY));
                    if (later.kind().equals("unknown")) propose(proposed, x, worked(later, later.family().isBlank() ? familyOf(id, later) : later.family(), later.family().isBlank() ? split(id, later.written())[1] : later.given(), "later", later.from(), later.event(), BASIS_FORMERLY));
                }
            }
        }

        /** The name of the person, other than the one at {@code self}, that the words before "formerly" end with; -1 when none does. */
        private static int laterNameIn(String head, List<Name> ns, int self) {
            String h = head.replaceAll("[\\s　]+", " ").strip().toLowerCase(Locale.ROOT);
            int found = -1, longest = 0;
            for (int k = 0; k < ns.size(); k++) {
                if (k == self) continue;
                for (String t : ns.get(k).texts()) {
                    String x = withoutNamesake(t).replaceAll("[\\s　]+", " ").strip().toLowerCase(Locale.ROOT);
                    if (x.isEmpty() || !h.endsWith(x) || x.length() <= longest) continue;
                    if (FamilyForms.script(x).equals("latin") && h.length() > x.length() && Character.isLetter(h.charAt(h.length() - x.length() - 1))) continue;
                    found = k; longest = x.length();
                }
            }
            return found;
        }

        /** The names with each proposal that nothing tore and that agrees with the others for that name. */
        private List<Name> settle(String id, List<Name> ns, Map<Integer, List<Name>> proposed, Set<Integer> torn) {
            List<Name> out = new ArrayList<>(ns);
            for (Map.Entry<Integer, List<Name>> p : proposed.entrySet()) {
                if (torn.contains(p.getKey())) continue;
                Name first = p.getValue().get(0);
                boolean agree = p.getValue().stream().allMatch(x -> x.kind().equals(first.kind()) && (x.from() == null || first.from() == null || !FamilyDate.apart(x.from(), first.from(), 1)));
                if (agree && !withdrawnAs(id, first)) out.set(p.getKey(), first);
            }
            return out;
        }

        /**
         * Whether the person disputed or retired a claim that gave this name this kind: then the family doubts it, which is a conflict in the
         * evidence, and the kind is asked, never worked out again from what is left of the same answer.
         */
        private boolean withdrawnAs(String id, Name worked) {
            for (Finding f : withdrawn.getOrDefault(id, List.of())) {
                String k = FamilyDetail.of(f).containsKey("kind") ? kind(FamilyDetail.get(f, "kind")) : kindFromWords(f.triple().predicate());
                if (k.equals(worked.kind()) && worked.isForm(written(f))) return true;
            }
            return false;
        }

        private static void propose(Map<Integer, List<Name>> proposed, int i, Name n) { proposed.computeIfAbsent(i, k -> new ArrayList<>()).add(n); }

        private static Name worked(Name n, String family, String given, String kind, FamilyDate from, String event, String basis) {
            return new Name(n.written(), family, given, n.forms(), kind, n.said(), from, false, n.to(), event, n.claims(), n.evidence(), n.accepted(), n.implicit(), true, basis);
        }

        /** Whether a person has, beside this name, another replacing name of another family part: otherwise no family part changed. */
        private boolean changed(String id, List<Name> ns, Name n, String fam) {
            for (Name o : ns) {
                if (o == n || !o.replaces()) continue;
                String of = familyOf(id, o);
                if (!of.isBlank() && !sameFamily(of, fam, n.japanese() || o.japanese())) return true;
                if (of.isBlank() && !FamilyNames.surnames(List.of(o.written(), n.written())).isEmpty()) return true;
            }
            return false;
        }

        /**
         * The family parts a person carried in a year, as far as their names say: the name they carried then; else, when every name of theirs
         * not taken after it has one family part, that one; empty when that is not known. {@code when} null: every name of theirs.
         */
        private List<String> familiesAt(String person, FamilyDate when) {
            Graph.Node node = g.node(person);
            if (node == null || FamilyQuestions.placeholder(node.label())) return List.of();
            if (when != null) {
                Name then = at(person, when.year());
                if (then != null) { String f = familyOf(person, then); return f.isBlank() ? List.of() : List.of(f); }
            }
            List<String> out = new ArrayList<>();
            for (Name h : names(person)) {
                if (!h.replaces() || (when != null && h.from() != null && h.from().year() > when.year())) continue;
                String f = familyOf(person, h);
                if (f.isBlank()) return List.of();
                if (out.stream().noneMatch(x -> sameFamily(x, f, true))) out.add(f);
            }
            return out.size() == 1 ? out : List.of();
        }

        /**
         * A name of the family part the husband or wife carried at their marriage, beside a name of another family part: dated from that
         * marriage, for a man and a woman alike. A name the source ties to its marriage (event=) takes that marriage's date. Only a marriage
         * to somebody of that family part then counts: a name the other took after the marriage (a will's name clause) dates nothing. Two
         * marriages into one family part: which is not known. How the name came is worked out as the marriage only when nothing points to more
         * ({@link #beyondMarriage}); where something does, only the year is worked out and how is the family's to say. Where a source says
         * how the person entered that family (婿養子, an adoption, 入夫), the entry settles the name ({@link #byEntry}). A record that writes
         * the name before the marriage leaves the year as it is: the questions ask about that record, since a later record often gives an
         * earlier fact (a birth) under the name carried when it was written.
         */
        private void byMarriage(String id, List<Name> ns, Map<Integer, List<Name>> proposed, Set<Integer> torn) {
            if (ns.size() < 2) return;
            List<Entry> in = null;
            for (int i = 0; i < ns.size(); i++) {
                Name n = ns.get(i);
                boolean open = n.kind().equals("unknown"), undated = n.kind().equals("marriage") && !n.dated();
                if (!open && !undated) continue;
                if (undated && !n.event().isBlank()) {
                    Finding e = finding(n.event());
                    FamilyDate d = e == null || e.triple() == null || !e.triple().predicate().equals("married-to") ? null : FamilyChecks.claimDate(e);
                    if (d != null) { propose(proposed, i, worked(n, n.family(), n.given(), "marriage", d, n.event(), BASIS_MARRIAGE_YEAR)); continue; }
                }
                String fam = familyOf(id, n);
                if (fam.isBlank() || (open && !changed(id, ns, n, fam))) continue;
                String married = null, spouse = null;
                FamilyDate when = null;
                // the husbands or wives of that family part: one marriage that two sources give, one dated and one not, is one marriage and its date
                Set<String> fitting = new HashSet<>();
                for (String[] sp : spouses.getOrDefault(id, List.of())) {
                    if (g.node(sp[0]) == null) continue;
                    Finding marriage = byId.get(sp[1]);
                    FamilyDate d = marriage == null ? null : FamilyChecks.claimDate(marriage);
                    if (familiesAt(sp[0], d).stream().noneMatch(h -> sameFamily(h, fam, n.japanese()))) continue;
                    fitting.add(sp[0]);
                    if (spouse == null || when == null && d != null || when != null && d != null && d.year() < when.year()) { married = sp[1]; spouse = sp[0]; when = d; }
                }
                if (fitting.size() != 1) continue;
                String given = n.family().isBlank() ? split(id, n.written())[1] : n.given();
                if (open) {
                    if (in == null) in = entered(id);
                    if (in.stream().anyMatch(e -> !e.kind().equals("marriage") && sameFamily(e.family(), fam, n.japanese()))) continue;
                    if (n.from() != null && when != null && FamilyDate.apart(n.from(), when, 1)) { torn.add(i); continue; }
                    // a name the claims write the person under before this marriage, beside another name that an earlier or other marriage
                    // explains, and no name at birth known: it may be the name they were born with (a widow whose second husband carried her
                    // own birth family name). Nothing is worked out, and the family is asked
                    if (when != null && ns.stream().noneMatch(x -> x.kind().equals("birth")) && writtenBefore(id, n, when) && anotherMarriedName(id, ns, n, fam)) { torn.add(i); continue; }
                    String event = n.event().isBlank() ? married : n.event();
                    if (!beyondMarriage(id, n, fam, spouse)) propose(proposed, i, worked(n, fam, given, "marriage", n.from() != null ? n.from() : when, event, BASIS_MARRIAGE));
                    else if (n.from() == null && when != null) propose(proposed, i, worked(n, fam, given, "unknown", when, event, BASIS_MARRIAGE_YEAR));
                } else if (when != null) propose(proposed, i, worked(n, n.family(), n.given(), "marriage", when, n.event().isBlank() ? married : n.event(), BASIS_MARRIAGE_YEAR));
            }
        }

        /** Whether a claim filed under one of this name's forms, other than a name claim, is dated before a year: the name was used before it. */
        private boolean writtenBefore(String id, Name n, FamilyDate when) {
            for (String c : about.getOrDefault(id, List.of())) {
                Finding f = byId.get(c);
                if (f == null || f.triple() == null || f.triple().predicate().equals(PREDICATE) || !n.isForm(f.triple().subject())) continue;
                FamilyDate d = FamilyChecks.claimDate(f);
                if (d != null && d.year() < when.year()) return true;
            }
            return false;
        }

        /** Whether another name of the person, of another family part, is of the family part a husband or wife carried at their marriage. */
        private boolean anotherMarriedName(String id, List<Name> ns, Name n, String fam) {
            for (Name o : ns) {
                if (o == n || !o.replaces()) continue;
                String of = familyOf(id, o);
                if (of.isBlank() || sameFamily(of, fam, n.japanese() || o.japanese())) continue;
                for (String[] sp : spouses.getOrDefault(id, List.of())) {
                    if (g.node(sp[0]) == null) continue;
                    Finding marriage = byId.get(sp[1]);
                    FamilyDate d = marriage == null ? null : FamilyChecks.claimDate(marriage);
                    if (familiesAt(sp[0], d).stream().anyMatch(h -> sameFamily(h, of, o.japanese()))) return true;
                }
            }
            return false;
        }

        /**
         * Whether anything points to more than a marriage for a person whose name {@code n} has the family part {@code fam} of the husband or
         * wife {@code spouse} (null when none is known): an adoption of theirs, or a word for one with the name; an entry into a family of that
         * name other than by birth, or as its head or heir; the spouse's parents recorded as their own parents; their being the heir of the
         * spouse's parent or of somebody of that family name. Then the name may have come as 婿養子, as 養女, by 入夫 marriage or on an adoption,
         * and how is the family's to say. The same for a man and a woman.
         */
        public boolean beyondMarriage(String id, Name n, String fam, String spouse) {
            if (!adoptions.getOrDefault(id, List.of()).isEmpty() || moreThanAMarriageSaid(id, n, fam, spouse)) return true;
            boolean jp = n.japanese();
            for (String[] m : memberships.getOrDefault(id, List.of())) {
                String of = FamilyHouses.nameOf(FamilyHouses.labelOf(g, m[0]));
                if (of.isBlank() || !sameFamily(of, fam, jp)) continue;
                Map<String, String> d = FamilyDetail.of(byId.get(m[1]));
                String role = d.getOrDefault("role", "").strip();
                if (!d.getOrDefault("how", "").strip().equals("birth") || role.equals("head") || role.equals("heir")) return true;
            }
            List<String> theirs = spouse == null ? List.of() : parents.getOrDefault(spouse, List.of());
            for (String p : parents.getOrDefault(id, List.of())) if (theirs.contains(p)) return true;
            // a parent who carried that family part at the birth, beside a parent who carried another: the person came into the family as
            // a child of it (養子), a tree writing the adoptive father beside the one by birth, whatever the marriage
            boolean ofIt = false, other = false;
            for (String p : parents.getOrDefault(id, List.of())) for (String pf : parentFamilies(p, born.get(id))) { if (sameFamily(pf, fam, jp)) ofIt = true; else other = true; }
            if (ofIt && other) return true;
            for (String h : heirOf.getOrDefault(id, List.of())) if (theirs.contains(h) || bore(h, fam, jp)) return true;
            return false;
        }

        /**
         * Whether a word for an adoption (養子, 養女, 婿養子, 入夫, adopted) or for entering or marrying into a family ({@link #ENTERED}) stands in
         * the words kept with a name, in the quotes of its claims, of the marriage it may date from (with this husband or wife, or any when
         * none is known, and the event the name is tied to), or of the person's memberships of a family of that name.
         */
        private boolean moreThanAMarriageSaid(String id, Name n, String fam, String spouse) {
            List<String> words = new ArrayList<>(List.of(n.said() == null ? "" : n.said()));
            Set<String> claims = new LinkedHashSet<>(n.claims());
            if (n.event() != null && !n.event().isBlank()) claims.add(n.event());
            for (String[] sp : spouses.getOrDefault(id, List.of())) if (spouse == null || sp[0].equals(spouse)) claims.add(sp[1]);
            for (String[] m : memberships.getOrDefault(id, List.of())) {
                String of = FamilyHouses.nameOf(FamilyHouses.labelOf(g, m[0]));
                if (!of.isBlank() && fam != null && !fam.isBlank() && sameFamily(of, fam, n.japanese())) claims.add(m[1]);
            }
            for (String c : claims) { Finding f = finding(c); if (f != null) words.add(FamilyChecks.quoteOf(f)); }
            for (String w : words) {
                if (w == null || w.isBlank()) continue;
                for (String k : List.of("adoptive", "mukoyoshi", "nyufu")) if (SAYS.get(k).matcher(w).find()) return true;
                if (ENTERED.matcher(w).find()) return true;
            }
            return false;
        }

        /** Whether a person carried this family part in any of their names. */
        private boolean bore(String person, String fam, boolean jp) {
            for (Name x : names(person)) { String f = familyOf(person, x); if (!f.isBlank() && sameFamily(f, fam, jp)) return true; }
            return false;
        }

        /**
         * A name of a kind taken AT its event (婿養子, 入夫 marriage, an adoption) that a source ties to that event (event=) and gives no year
         * of its own takes the event's date, as a married name takes its marriage's ({@link #byMarriage}): 森田健二, taken on the adoption of
         * 1932. A record written under the name before that date is evidence against it, and the name stays a question.
         */
        private void byEvent(String id, List<Name> ns, Map<Integer, List<Name>> proposed, Set<Integer> torn) {
            for (int i = 0; i < ns.size(); i++) {
                Name n = ns.get(i);
                if (!TAKEN_AT_ENTRY.contains(n.kind()) || n.dated() || n.event().isBlank()) continue;
                Finding e = finding(n.event());
                if (e == null || e.triple() == null || !ENTRY_EVENTS.contains(e.triple().predicate())) continue;
                FamilyDate d = FamilyChecks.claimDate(e);
                if (d == null) continue;
                if (olderRecord(id, n, d)) { torn.add(i); continue; }
                propose(proposed, i, worked(n, n.family(), n.given(), n.kind(), d, n.event(), BASIS_ENTRY_YEAR));
            }
        }

        /** Whether a record written under a name is older than the event a name is worked out from: evidence against working it out. */
        private boolean olderRecord(String id, Name n, FamilyDate event) {
            int[] used = useWindow(id, n);
            return used != null && event != null && used[0] < event.year();
        }

        /** The kinds of name taken at the entry into a family, and the claims of such an entry a name can be tied to. */
        private static final Set<String> TAKEN_AT_ENTRY = Set.of("mukoyoshi", "nyufu", "adoptive"), ENTRY_EVENTS = Set.of("adopted-by", "married-to", FamilyHouses.MEMBER);

        /**
         * The families a person entered as a claim says how: a membership with the way they came in, and an adoption of a known kind into the
         * family the adopter belonged to at the adoption, by his names then, or by his one membership where his names give no family. Where
         * the family's own word says what kind of adoption brought them into a family in a year, what an older claim's words say of that
         * entry counts no more: the family's answer "an ordinary adoption" outranks a book's 婿養子.
         */
        private List<Entry> entered(String id) {
            List<Entry> out = new ArrayList<>();
            for (String[] m : memberships.getOrDefault(id, List.of())) {
                Finding f = byId.get(m[1]);
                Map<String, String> d = FamilyDetail.of(f);
                String kind = entryKind(d.getOrDefault("how", ""));
                String fam = FamilyHouses.nameOf(FamilyHouses.labelOf(g, m[0]));
                if (kind.isEmpty() || fam.isBlank()) continue;
                String from = d.getOrDefault("from", "");
                FamilyDate when = from.equalsIgnoreCase("event") ? eventDate(d.getOrDefault("event", "")) : !from.isBlank() ? FamilyDate.parse(from) : f == null ? null : FamilyChecks.claimDate(f);
                out.add(new Entry(fam, kind, when, m[1], word(f)));
            }
            for (String[] a : adoptions.getOrDefault(id, List.of())) {
                Finding f = byId.get(a[1]);
                String kind = adoptedKind(adoptionKind(f));
                if (kind.isEmpty() || f == null) continue;
                String from = FamilyDetail.get(f, "from");
                FamilyDate when = !from.isBlank() && !from.equalsIgnoreCase("event") ? FamilyDate.parse(from) : FamilyChecks.claimDate(f);
                // the family the adopter's names give at the adoption; where they give none, the one family he is a member of. A man born
                // into one family who entered his wife's as 婿養子 is of hers by his names then; two families and no name to choose: asked
                Set<String> families = new LinkedHashSet<>(familiesAt(a[0], when));
                if (families.isEmpty()) {
                    Set<String> of = new LinkedHashSet<>();
                    for (String[] m : memberships.getOrDefault(a[0], List.of())) { String fam = FamilyHouses.nameOf(FamilyHouses.labelOf(g, m[0])); if (!fam.isBlank()) of.add(fam); }
                    if (of.size() == 1) families = of;
                }
                for (String fam : families) out.add(new Entry(fam, kind, when, a[1], word(f)));
            }
            List<Entry> words = out.stream().filter(Entry::word).toList();
            out.removeIf(e -> !e.word() && words.stream().anyMatch(w -> sameFamily(w.family(), e.family(), true) && (w.from() == null || e.from() == null || w.from().year() == e.from().year())));
            return out;
        }

        /**
         * Whether a claim is the family's own word on how an adoption came: an adoption the person accepted that states its kind (ordinary, as
         * heir, as 婿養子). A plain "adopted into the family" says nothing against 婿養子, which is an adoption too.
         */
        private static boolean word(Finding f) {
            return f != null && f.state() == Finding.State.accepted && f.review() != null && "person".equals(f.review().reviewer()) && f.triple() != null
                    && f.triple().predicate().equals("adopted-by") && Set.of("ordinary", "heir", "mukoyoshi").contains(FamilyDetail.get(f, "kind"));
        }

        /**
         * A name of the family a person entered, as a claim says how (a membership, or an adoption into the adopter's family), takes that
         * entry's kind and year: 森田健二, who entered the 森田 family as 婿養子 in 1932. A name whose kind a claim gives takes only the year,
         * and only from an entry of that kind. A marriage into a family says nothing of how the name came (婿養子, 養女, or only the name at
         * the marriage), for a man and a woman alike: only its year is worked out, and how is asked. One entry that fits two of the person's
         * names settles neither.
         * A record that writes the name before the entry leaves the worked-out year as it is: the questions ask about that record. A person's
         * only name comes from the entry too when the entry brought them in from outside (婿養子, 入夫, an adoption, a marriage; a succession
         * never does) and every birth parent recorded carried another family part: 森田健二, the son of 遠藤正一, known by no other name.
         */
        private void byEntry(String id, List<Name> ns, Map<Integer, List<Name>> proposed, Set<Integer> torn) {
            List<Entry> in = entered(id);
            if (in.isEmpty()) return;
            Map<Entry, List<Integer>> fitting = new LinkedHashMap<>();
            for (int i = 0; i < ns.size(); i++) {
                Name n = ns.get(i);
                if (!n.replaces() || n.kind().equals("birth")) continue;
                boolean open = n.kind().equals("unknown");
                if (!open && n.dated()) continue;
                String fam = familyOf(id, n);
                // a sole name is dated from an entry only when the person was born outside that family and the entry brought them in
                boolean sole = open && !changed(id, ns, n, fam);
                if (fam.isBlank() || (sole && !bornOutside(id, ns, n, fam))) continue;
                String given = n.family().isBlank() ? split(id, n.written())[1] : n.given();
                for (Entry e : in) {
                    if (!sameFamily(e.family(), fam, n.japanese())) continue;
                    if (!open && !e.kind().equals(n.kind())) continue;
                    if (sole && !FROM_OUTSIDE.contains(e.kind())) continue;
                    fitting.computeIfAbsent(e, k -> new ArrayList<>()).add(i);
                    if (open) {
                        if (n.from() != null && e.from() != null && FamilyDate.apart(n.from(), e.from(), 1)) { torn.add(i); continue; }
                        if (e.kind().equals("marriage")) {
                            if (n.from() == null && e.from() != null) propose(proposed, i, worked(n, fam, given, "unknown", e.from(), n.event().isBlank() ? e.claim() : n.event(), BASIS_ENTRY_YEAR));
                            continue;
                        }
                        propose(proposed, i, worked(n, fam, given, e.kind(), n.from() != null ? n.from() : e.from(), n.event().isBlank() ? e.claim() : n.event(), BASIS_ENTRY));
                    } else if (e.kind().equals(n.kind()) && e.from() != null) propose(proposed, i, worked(n, n.family(), n.given(), n.kind(), e.from(), n.event().isBlank() ? e.claim() : n.event(), BASIS_ENTRY_YEAR));
                }
            }
            for (List<Integer> names : fitting.values()) if (names.size() > 1) torn.addAll(names);
        }

        /** The ways into a family that bring a person in from another one; a succession or a birth into it brings nobody in. */
        private static final Set<String> FROM_OUTSIDE = Set.of("mukoyoshi", "nyufu", "adoptive", "marriage");

        /**
         * Whether a person's only name is of a family part they were not born into: the family parts of their birth are known ({@link
         * #birthFamilies}), and none is this one, and every birth parent recorded carried another family part at the birth. A parent whose
         * family is not known, such as one written by the given name alone, may have given the name. Then the name came later, and an
         * entry into that family from outside says when.
         */
        private boolean bornOutside(String id, List<Name> ns, Name n, String fam) {
            if (ns.stream().filter(Name::replaces).count() != 1) return false;
            FamilyDate b = born.get(id);
            List<String> theirs = birthFamilies(id, b);
            if (theirs.isEmpty() || theirs.stream().anyMatch(t -> sameFamily(t, fam, n.japanese()))) return false;
            for (String p : parents.getOrDefault(id, List.of())) if (parentFamilies(p, b).isEmpty()) return false;
            return true;
        }

        /**
         * The family parts a person was born into: the family part each birth parent carried at the birth (or their only one), the families
         * each parent was a member of then, and a family the person's own membership says they were born into.
         */
        private List<String> birthFamilies(String id, FamilyDate b) {
            List<String> out = new ArrayList<>();
            for (String p : parents.getOrDefault(id, List.of())) out.addAll(parentFamilies(p, b));
            for (String[] m : memberships.getOrDefault(id, List.of())) {
                if (!FamilyDetail.get(byId.get(m[1]), "how").equals("birth")) continue;
                String fam = FamilyHouses.nameOf(FamilyHouses.labelOf(g, m[0]));
                if (!fam.isBlank()) out.add(fam);
            }
            return out;
        }

        /**
         * The family parts a birth parent carried at the birth: by their names then (or their only one), the family name a source writes them
         * by alone ("森田健二's father (written only as 遠藤)"), and the families they were a member of then.
         */
        private List<String> parentFamilies(String p, FamilyDate b) {
            List<String> out = new ArrayList<>(familiesAt(p, b));
            String[] mention = g.node(p) == null ? null : FamilyMentions.parts(g.node(p).label());
            if (mention != null && !mention[2].isBlank()) out.add(mention[2]);
            for (String[] m : memberships.getOrDefault(p, List.of())) {
                Finding f = byId.get(m[1]);
                Map<String, String> d = FamilyDetail.of(f);
                FamilyDate from = FamilyDate.parse(d.getOrDefault("from", "")), to = FamilyDate.parse(d.getOrDefault("to", ""));
                if (b != null && ((from != null && from.year() > b.year()) || (to != null && to.year() <= b.year()))) continue;
                String fam = FamilyHouses.nameOf(FamilyHouses.labelOf(g, m[0]));
                if (!fam.isBlank()) out.add(fam);
            }
            return out;
        }

        /**
         * The first name, of the family part a birth parent carried at the birth, is the name at birth: 森田正二, Isamu's son, born 森田 and
         * later 髙橋. Only where no name is the name at birth yet, the person carried another family part too, and exactly one name fits: the
         * earliest, dated at the birth or not at all, beside a later name of another family part whose kind and year a claim gives or the
         * other facts settle (髙橋正二, on adoption in 1940; Mary Ellis, from her marriage in 1875). Two names that nothing orders settle
         * nothing: a narrative's "son" does not say whether he was born to that parent or adopted, and which name came first is a question.
         * Two names that fit: which was first is a question too. {@code settled}: the names as the other rules settle them.
         */
        private void atBirth(String id, List<Name> ns, List<Name> settled, Map<Integer, List<Name>> proposed) {
            if (ns.stream().anyMatch(n -> n.kind().equals("birth"))) return;
            List<Name> replacing = ns.stream().filter(Name::replaces).toList();
            if (replacing.size() < 2) return;
            FamilyDate b = born.get(id);
            List<String> theirs = birthFamilies(id, b);
            if (theirs.isEmpty()) return;
            int found = -1;
            for (int i = 0; i < ns.size(); i++) {
                Name n = ns.get(i);
                if (!n.replaces() || !n.kind().equals("unknown")) continue;
                String fam = familyOf(id, n);
                if (fam.isBlank() || theirs.stream().noneMatch(t -> sameFamily(t, fam, n.japanese()))) continue;
                if (n.from() != null && ((b != null && n.from().year() > b.year() + 1) || replacing.stream().anyMatch(o -> o != n && o.from() != null && o.from().year() < n.from().year()))) continue;
                if (!changed(id, ns, n, fam)) continue;
                if (!explainedLater(id, settled, i, fam, b, n.japanese())) continue;
                if (found >= 0) return;
                found = i;
            }
            if (found < 0) return;
            Name n = ns.get(found);
            String fam = familyOf(id, n);
            propose(proposed, found, worked(n, fam, n.family().isBlank() ? split(id, n.written())[1] : n.given(), "birth", n.from() != null ? n.from() : b, n.event(), BASIS_BIRTH));
        }

        /**
         * Whether the person has, beside the name at {@code at}, a later name of another family part whose kind and year a claim gives or
         * the other facts settle (not the name at birth), after the birth when the birth is dated: the change that puts the name before it.
         */
        private boolean explainedLater(String id, List<Name> settled, int at, String fam, FamilyDate b, boolean japanese) {
            for (int k = 0; k < settled.size(); k++) {
                Name o = settled.get(k);
                if (k == at || !o.replaces() || !o.explained() || o.kind().equals("birth") || o.from() == null) continue;
                if (b != null && o.from().year() <= b.year()) continue;
                String of = familyOf(id, o);
                if (!of.isBlank() && !sameFamily(of, fam, japanese || o.japanese())) return true;
            }
            return false;
        }

        /** The names in order, and each replacing name's end: its own, else the year the next replacing name began. */
        private static List<Name> ordered(List<Name> ns) {
            List<Name> sorted = new ArrayList<>(ns);
            // a name no source gives, undated, is earlier than a dated name that replaced it: the latest name is the dated one (latest), so
            // 森田正二, the name the entry is filed under, comes before 髙橋正二, taken in 1940. A name known only by its end comes before the
            // name that began then
            boolean dated = ns.stream().anyMatch(n -> n.replaces() && !n.implicit() && n.from() != null && !n.kind().equals("birth"));
            sorted.sort(Comparator.comparingInt((Name n) -> n.kind().equals("birth") ? 0 : 1)
                    .thenComparingLong(n -> n.from() != null ? n.from().year() * 2L + 1 : n.to() != null ? n.to().year() * 2L : dated && n.implicit() && n.replaces() ? Long.MIN_VALUE : Long.MAX_VALUE)
                    .thenComparingInt(Index::rankOf));
            List<Name> out = new ArrayList<>();
            for (int i = 0; i < sorted.size(); i++) {
                Name n = sorted.get(i);
                if (n.to() != null || !n.replaces()) { out.add(n); continue; }
                FamilyDate next = null;
                boolean birth = n.kind().equals("birth");
                int start = n.from() == null ? (birth ? Integer.MIN_VALUE : Integer.MAX_VALUE) : n.from().year();
                for (int k = i + 1; k < sorted.size(); k++) {
                    Name m = sorted.get(k);
                    if (!m.replaces() || m.from() == null || m.from().year() < start || m.kind().equals("birth")) continue;
                    // a later name of the same year ends the name at birth that year (an adoption in the year of birth), and no other name
                    if (m.from().year() == start && n.from() != null && !birth) continue;
                    next = m.from();
                    break;
                }
                out.add(next == null ? n : new Name(n.written(), n.family(), n.given(), n.forms(), n.kind(), n.said(), n.from(), n.fromEvent(), next, n.event(), n.claims(), n.evidence(), n.accepted(), n.implicit(), n.workedOut(), n.basis()));
            }
            return out;
        }
    }
}
