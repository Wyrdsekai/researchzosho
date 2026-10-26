package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Links across dossiers (0.6.6): a name that the claims of two subjects both carry, found mechanically. A company's chief executive and the
 * library's keeper both worked at the same firm in the same years; the shelves held both facts in two dossiers and no run connected them
 * (2026-10-24). The library does not guess what the connection is: it says who shares the name, cites the claims on each side, and files the
 * question for a run or the person to answer. Computed from the shelves whenever a dossier is read, and filed as open questions when a
 * dossier report lands.
 */
public final class CrossLinks {
    private CrossLinks() { }

    /** A name two subjects' claims share: the name, the other subject, the claims on each side. */
    public record Link(String name, String other, List<String> ownClaims, List<String> otherClaims) { }

    /** A proper name as a claim writes it: one or more capitalised words, or an all-capitals word of three letters or more. */
    /** A Japanese name as a claim writes it: a run of kanji or katakana, two to twelve characters, a title after it (首相, 氏) stripped. */
    static final Pattern NAME_JA = Pattern.compile("[\\p{IsHan}\\p{IsKatakana}ー]{2,12}");
    static final Set<String> STOP_JA = Set.of("株式会社", "有限会社", "合同会社", "会社", "大学", "日本", "東京", "政府", "国会", "内閣", "衆議院", "参議院", "議員", "大臣", "首相", "総理", "社長", "会長", "代表", "取締役", "出身", "卒業", "就任", "辞任",
            "設立", "創業", "本社", "支社", "部長", "課長", "日本語", "英語", "世界", "国内", "海外", "以降", "以前", "現在", "当時", "東京都", "大阪府", "京都府", "北海道", "昭和", "平成", "令和", "年度", "株式", "法人", "公開", "発表", "開始", "終了");
    static final String[] TITLE_JA = {"首相", "総理", "大臣", "議員", "氏", "様", "社長", "会長", "教授", "博士", "代表", "取締役", "知事", "市長", "総裁", "頭取", "理事長", "学長", "会頭", "長官", "長"};
    /** A Japanese place as an address writes it: a prefecture (都道府県) or a municipality (市区町村). */
    static final Pattern PLACE_JA = Pattern.compile("[\\p{IsHan}]{1,6}(?:都|道|府|県)|[\\p{IsHan}]{1,8}(?:市|区|町|村)");
    /** The prefectures by their full names; Tokyo's wards and the designated cities, which an address names without a prefecture. */
    static final Set<String> PREFECTURES_JA = Set.of("北海道", "青森県", "岩手県", "宮城県", "秋田県", "山形県", "福島県", "茨城県", "栃木県", "群馬県", "埼玉県", "千葉県", "東京都", "神奈川県", "新潟県", "富山県",
            "石川県", "福井県", "山梨県", "長野県", "岐阜県", "静岡県", "愛知県", "三重県", "滋賀県", "京都府", "大阪府", "兵庫県", "奈良県", "和歌山県", "鳥取県", "島根県", "岡山県", "広島県", "山口県", "徳島県",
            "香川県", "愛媛県", "高知県", "福岡県", "佐賀県", "長崎県", "熊本県", "大分県", "宮崎県", "鹿児島県", "沖縄県");
    static final Set<String> CITIES_JA = Set.of("千代田区", "中央区", "港区", "新宿区", "文京区", "台東区", "墨田区", "江東区", "品川区", "目黒区", "大田区", "世田谷区", "渋谷区", "中野区", "杉並区", "豊島区", "北区",
            "荒川区", "板橋区", "練馬区", "足立区", "葛飾区", "江戸川区", "札幌市", "仙台市", "さいたま市", "千葉市", "横浜市", "川崎市", "相模原市", "新潟市", "静岡市", "浜松市", "名古屋市", "京都市", "大阪市", "堺市",
            "神戸市", "岡山市", "広島市", "北九州市", "福岡市", "熊本市");
    /** A municipality as an address writes it, after its prefecture: 奈良県天理市 names 天理市. */
    static final Pattern PLACE_IN_PREFECTURE_JA = Pattern.compile("(?:北海道|東京都|京都府|大阪府|[\\p{IsHan}]{2,3}県)([\\p{IsHan}ぁ-ん]{1,5}(?:市|区|町|村))");

    /**
     * The Japanese places a text names: a prefecture by its name, a ward or a designated city by theirs, any municipality after its
     * prefecture. A surname that ends in 市 (高市) and an office that ends in 府 (内閣府) look like places and are none.
     */
    static List<String> placesJa(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        for (String p : PREFECTURES_JA) if (text.contains(p) && !STOP_JA.contains(p) && !out.contains(p)) out.add(p);   // the capital is every dossier's: the ward is the place
        for (String c : CITIES_JA) if (text.contains(c) && !out.contains(c)) out.add(c);
        Matcher m = PLACE_IN_PREFECTURE_JA.matcher(text);
        while (m.find()) { String c = m.group(1); if (!STOP_JA.contains(c) && !out.contains(c)) out.add(c); }
        return out;
    }

    static final Pattern NAME = Pattern.compile("\\b(?:[A-Z][A-Za-z0-9&'’.-]{2,}(?:\\s+(?:of|and|&|de|the)\\s+)?(?:\\s+[A-Z][A-Za-z0-9&'’.-]{2,})*|[A-Z]{3,})\\b");
    /** Words that name nothing in particular, or everything: months, legal forms, the words a dossier's own skeleton uses. */
    static final Set<String> STOP = Set.of("The", "And", "Inc", "Inc.", "LLC", "Ltd", "Ltd.", "Corp", "Corp.", "Corporation", "Company", "Group", "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December",
            "Jan", "Feb", "Mar", "Apr", "Jun", "Jul", "Aug", "Sep", "Sept", "Oct", "Nov", "Dec", "United States", "US", "USA", "American", "America", "English", "Japan", "Japanese", "Co-founder", "Co-Founder", "Founder", "CEO", "CTO", "CFO", "COO", "CRO", "VP", "President", "Chairman", "Director", "Senior", "Chief", "Officer",
            "Engineer", "Engineering", "Software", "Manager", "Vice", "Technical", "Staff", "Member", "Advisor", "Board", "Board of", "Series", "Seed", "Form", "Section", "Timeline", "Identity", "Public", "Record", "Current", "Reading", "Answer", "Sources", "References", "Claims", "Dossier", "Story",
            // the lenses: the registers, regulators and reference publishers every dossier cites are where the library looked, not a connection between the things it looked at
            "LinkedIn", "Wikipedia", "Wikidata", "Google", "GitHub", "Twitter", "Bluesky", "YouTube", "Internet Archive", "Hacker News", "SEC", "EDGAR", "FEC", "ORCID", "OpenAlex", "GLEIF", "CourtListener", "Radaris", "ZoomInfo", "Wellfound", "Crunchbase", "Tracxn", "PitchBook",
            "ProPublica", "GuideStar", "Candid", "IRS", "USPTO", "Justia", "OpenCorporates", "PACER", "Bloomberg", "Reuters", "Forbes", "Companies House", "EDINET", "gBizINFO", "Wayback Machine", "Archive.org", "Google Patents", "Google Books", "Google Scholar",
            "Semantic Scholar", "WorldCat", "Goodreads", "IMDb", "Facebook", "Instagram", "Reddit", "Mastodon", "Substack", "Medium", "TikTok", "Form 990",
            // the government's bodies and the registers they keep: a regulator two subjects answer to is not a connection between them
            "OpenSanctions", "Regulations.gov", "Federal Register", "USAspending", "Congress", "Senate", "House of Representatives", "Supreme Court", "FTC", "FCC", "FDA", "EPA", "DOJ", "DOD", "DoD", "GAO", "CMS", "HHS",
            "NTIA", "NIST", "NIH", "NSF", "DARPA", "CIA", "FBI", "NSA", "OSHA", "DOE", "DOT", "Treasury", "Commerce", "Pentagon",
            // credentials after a name: two people with an MD or a CFP are not connected by it
            "MD", "PhD", "Ph.D.", "MBA", "JD", "MPH", "CFA", "CFP", "CPA", "FACS", "FACP", "FAAN", "MACP", "DrPH", "RN", "DDS", "DVM", "DO", "MSc", "BSc", "BA", "MA", "MS", "BS", "LLM", "LLB", "Esq",
            "FRS", "FRCP", "OBE", "CBE", "KBE", "MSW", "MFA", "MDiv", "EdD", "PharmD", "ScD", "MPA", "MPP", "MEd", "Jr", "Jr.", "Sr", "Sr.", "II", "III", "IV",
            "No", "None", "Not", "Yes", "Chair", "Chairs", "Name", "Founded", "Headquarters", "Address", "Website", "Phone", "Email", "Registry", "Status", "NYC", "NY", "DC", "LA", "SF", "UK", "EU", "Active", "Inactive", "Issued", "Lapsed", "Retired", "Pending", "Merged", "Dissolved", "Wikimedia Commons", "Wikimedia", "Commons", "This", "That", "These", "Those", "There", "Their", "They", "His", "Her", "Its", "Our", "Who", "What", "When", "Where", "Which", "Why", "How", "Early", "Late", "Mid", "From", "With", "For", "After", "Before", "Since", "Until", "Between",
            "University", "College", "School", "Institute", "Department", "Center", "Centre", "City", "State", "County", "Street", "Avenue", "Suite", "Box", "PMB",
            // the big places: everyone is somewhere, and a city two dossiers share is rarely the connection worth a question
            "Boston", "Cambridge", "New York", "New York City", "San Francisco", "San Jose", "Seattle", "Los Angeles", "Chicago", "Washington", "London", "Tokyo", "Paris", "Berlin",
            "Massachusetts", "California", "Texas", "Delaware", "Virginia", "Maryland", "Florida", "Iowa", "Ohio", "Michigan", "Illinois", "Oregon", "Colorado", "Georgia", "Pennsylvania", "England", "Europe", "Asia", "China", "India", "Canada", "France", "Germany",
            "Greater", "Metro", "Area", "Region",
            // the registers' own words and the shapes of a dossier's labels
            "LEI", "CIK", "EIN", "SIC", "NTEE", "DUNS", "ISBN", "DOI", "URL", "PDF", "HTTP", "API", "JSON", "HTML", "R&D", "M&A", "IPO", "AI", "ML", "IT", "HR", "PR", "UX", "UI", "IoT", "SaaS", "B2B", "B2C",
            "Namesakes", "Namesake", "Legal", "Prior", "Founding", "Founders", "Co-founders", "Co-authors", "Co-author", "Note", "Notes", "Source", "Caveat", "Caveats", "Conflicts", "Evidence", "Searched", "Looked");

    /** A plain capitalised word that names a thing in general, not a particular one: the words every dossier uses. A single plain word links only when it is not one of these. */
    static final Set<String> COMMON = Set.of("Patent", "Patents", "Founded", "Founder", "Systems", "System", "Research", "Development", "Technology", "Technologies", "Products", "Product", "Services", "Service", "Solutions",
            "Software", "Hardware", "Network", "Networks", "Data", "Digital", "Media", "Mobile", "Cloud", "Security", "Finance", "Financial", "Capital", "Partners", "Ventures", "Labs", "Lab", "Studio", "Studios", "Games", "Game", "Design", "Designer",
            "Marketing", "Sales", "Operations", "Strategy", "Management", "Business", "Commerce", "Industry", "Industries", "International", "Global", "National", "General", "Electric", "Energy", "Health", "Medical", "Education", "Press", "Books", "Book",
            "Novel", "Author", "Writer", "Editor", "Publisher", "Publishing", "Interview", "Podcast", "Video", "Conference", "Summit", "Award", "Awards", "Fellow", "Professor", "Doctor", "Dr", "Mr", "Mrs", "Ms", "Born", "Died", "Married", "Graduated",
            "Joined", "Left", "Moved", "Became", "Served", "Worked", "Acquired", "Acquisition", "Merger", "Raised", "Round", "Revenue", "Employees", "Headquarters", "Office", "Offices", "Report", "Reports", "Filing", "Filings", "Profile", "Summary",
            "Overview", "History", "Career", "Life", "Work", "Role", "Roles", "Title", "Position", "Team", "People", "Person", "Family", "Son", "Daughter", "Father", "Mother", "Wife", "Husband", "Brother", "Sister", "Uncle", "Aunt", "Partner", "Colleague");

    /** The dossier subjects the shelves have reports on, each with the claims naming it and its reports, in one pass over the shelves. */
    record Shelf(List<Finding> claims, List<Investigation> reports) { }

    static Map<String, Shelf> subjects(LibraryStore store) throws IOException {
        Map<String, Shelf> out = new LinkedHashMap<>();
        if (!Files.isDirectory(store.investigationsDir())) return out;
        Map<String, List<Investigation>> byName = new LinkedHashMap<>();
        List<Path> files; try (var s = Files.list(store.investigationsDir())) { files = s.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().toList(); }
        for (Path p : files) {
            Investigation inv; try { inv = store.investigation(p.getFileName().toString().replaceFirst("\\.md$", "")); } catch (RuntimeException | IOException e) { continue; }
            if (inv == null || inv.title().startsWith("The story of ")) continue;
            String s = TimelineClaims.subjectOf(inv.title());
            if (s != null && !s.isBlank()) byName.computeIfAbsent(s, k -> new ArrayList<>()).add(inv);
        }
        if (byName.isEmpty()) return out;
        for (var e : byName.entrySet()) out.put(e.getKey(), new Shelf(new ArrayList<>(), e.getValue()));
        Map<String, String> core = new LinkedHashMap<>(); for (String k : out.keySet()) core.put(k, coreName(k));
        for (Finding f : store.scanFindings().findings()) {
            if (f.state() == Finding.State.retired || f.state() == Finding.State.superseded) continue;
            String t = (f.title() + "\n" + f.body()).toLowerCase(Locale.ROOT);
            for (var e : out.entrySet()) if (t.contains(core.get(e.getKey()))) e.getValue().claims().add(f);
        }
        return out;
    }

    /** A subject's name without its legal form, lowercased, as the claims write it: "日本製鉄株式会社" is "日本製鉄" on the shelves, "Acme Corp., Inc." is "acme corp". */
    static String coreName(String subject) {
        String s = subject.strip().replaceAll("(?i)[,\\s]+(?:inc\\.?|corp\\.?|corporation|llc|ltd\\.?|limited|plc|gmbh|co\\.,? ltd\\.?|k\\.k\\.)$", "").replaceAll("^(?:株式会社|有限会社|合同会社)|(?:株式会社|有限会社|合同会社)$", "").strip();
        return (s.length() >= 2 ? s : subject.strip()).toLowerCase(Locale.ROOT);
    }

    /** The sections of a report where the people and the organisations are named; the rest of a report names the world at large. */
    static final String[] NAMED_SECTIONS = {"Identity", "People", "Roles", "Associations", "Structure", "Registry card", "Holdings and filings"};

    /** The names in a subject's reports, in the sections that name people and organisations, each with the report that carries it. */
    static Map<String, List<String>> namesInReports(List<Investigation> reports, String subject) {
        Map<String, List<String>> out = new HashMap<>();
        for (Investigation inv : reports) for (String sec : NAMED_SECTIONS) {
            String text = TimelineClaims.section(inv.body(), sec);
            if (text.isEmpty()) continue;
            for (String n : names(text, subject)) { List<String> l = out.computeIfAbsent(n, k -> new ArrayList<>()); if (!l.contains(inv.id())) l.add(inv.id()); }
        }
        return out;
    }

    /** The names in a claim worth linking on: proper names, not the subject's own and not the stop words. */
    static Set<String> namesIn(Finding f, String subject) { return names(f.title() + "\n" + f.body(), subject); }

    /**
     * The names in a text worth linking on. A name of several words, an acronym (WHERE, EEDAR) or a word with a capital inside it (PayPal,
     * MocoSpace) is taken as it stands; a single plain capitalised word only when it is not one of the words every dossier uses (Patent,
     * Founded). A trailing "and", "of" or "the" is not part of a name, and the subject's own name is not a link to itself.
     */
    /** A place written as an address writes it, "Brookline, MA", "Chestnut Hill, Massachusetts": everyone is somewhere, and a town two dossiers share is a place, not a connection. */
    static final Pattern PLACE = Pattern.compile("\\b([A-Z][a-z]+(?:\\s[A-Z][a-z]+)?),\\s(?:(?:A[KLRZ]|C[AOT]|D[CE]|FL|GA|HI|I[ADLN]|K[SY]|LA|M[ADEINOST]|N[CDEHJMVY]|O[HKR]|PA|RI|S[CD]|T[NX]|UT|V[AT]|W[AIVY]|AB|BC|MB|NB|NL|NS|NT|NU|ON|PE|QC|SK|YT)\\b|Massachusetts|California|New York|Texas|Illinois|Washington|Virginia|Maryland|Florida|Ohio|Michigan|Pennsylvania|Georgia|Colorado|Oregon|Arizona|Minnesota|Wisconsin|Connecticut|New Jersey|North Carolina|South Carolina|Tennessee|Missouri|Indiana|Kentucky|Alabama|Louisiana|Oklahoma|Utah|Nevada|Iowa|Kansas|Nebraska|Idaho|Maine|Vermont|New Hampshire|Rhode Island|Delaware|Hawaii|Alaska|Montana|Wyoming|New Mexico|North Dakota|South Dakota|England|Scotland|Wales|Ontario|Quebec|British Columbia|Japan|Germany|France|Italy|Spain|Canada|Australia)");

    /** The words an organisation's name ends in: "Columbia University, New York" names no town, whatever follows the comma. */
    static final Set<String> ORG_WORDS = Set.of("University", "College", "Institute", "School", "Hospital", "Company", "Corporation", "Inc", "Corp", "LLC", "Bank", "Group", "Foundation", "Museum", "Library", "Center", "Centre", "Church", "Academy", "Laboratory", "Press", "Trust", "Fund", "Partners", "Associates", "Clinic");
    static boolean isATown(String place) { String[] w = place.strip().split("\\s+"); return w.length > 0 && !ORG_WORDS.contains(w[w.length - 1]); }

    static Set<String> names(String text, String subject) {
        Set<String> out = new HashSet<>();
        Set<String> own = new HashSet<>(); for (String w : subject.split("[\\s,]+")) if (w.length() > 2) own.add(w.toLowerCase(Locale.ROOT));
        Set<String> places = new HashSet<>(); Matcher pl = PLACE.matcher(text); while (pl.find()) if (isATown(pl.group(1))) places.add(pl.group(1));
        Matcher ja = NAME_JA.matcher(text);
        while (ja.find()) {
            String name = ja.group();
            for (String t : TITLE_JA) if (name.endsWith(t) && name.length() > t.length() + 1) name = name.substring(0, name.length() - t.length());
            boolean kanjiOnly = name.chars().allMatch(ch -> Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN);
            if (name.length() < (kanjiOnly ? 3 : 2) || STOP_JA.contains(name) || PLACE_JA.matcher(name).matches() || subject.contains(name) || name.contains(subject.strip())) continue;   // a two-kanji word is a common noun more often than a name
            out.add(name);
        }
        Matcher m = NAME.matcher(text);
        while (m.find()) {
            String name = m.group().split("(?<=[A-Za-z]{3})\\.\\s+(?=[A-Z])")[0];   // a sentence ends at a dot after a word, and the next sentence's first word is not part of the name ("Zatkin. Its"); an initial's dot is ("J. R. Tolkien")
            name = name.replaceAll("[.,;:]+$", "").replaceAll("['’]s$", "").replaceAll("\\s+(?:and|of|the|&|de)$", "").strip();   // nor a possessive's s
            if (name.length() < 3 || STOP.contains(name) || COMMON.contains(name) || places.contains(name)) continue;
            if (name.matches("^[A-Z0-9&.-]{3,}$") && STOP.contains(name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT))) continue;   // a registry's status word in capitals (ACTIVE, NOT) is the stop word it spells
            boolean isOwn = true; for (String w : name.split("\\s+")) if (!own.contains(w.toLowerCase(Locale.ROOT)) && !STOP.contains(w)) { isOwn = false; break; }
            if (isOwn) continue;
            boolean oneWord = !name.contains(" ");
            if (oneWord) {
                boolean acronym = name.matches("^[A-Z0-9&.-]{3,}$"), innerCapital = name.matches("^[A-Z][a-z0-9]+[A-Z].*$"), plain = name.matches("^[A-Z][a-z'’-]+$");
                if (plain && (name.length() < 5 || COMMON.contains(name))) continue;
                if (!acronym && !innerCapital && !plain) continue;
                // a plain word is a name only where it stands inside a sentence ("at Placester"), not where it opens one or labels one ("Namesakes:")
                if (plain && !Pattern.compile("[a-z,(]\\s+" + Pattern.quote(name) + "(?![a-z:])").matcher(text).find()) continue;
            } else {
                boolean allStop = true; for (String w : name.split("\\s+")) if (!STOP.contains(w) && !COMMON.contains(w) && !w.matches("(?i)and|of|the|&|de")) { allStop = false; break; }
                if (allStop) continue;
            }
            out.add(name);
        }
        return out;
    }

    /** The names of one shelf: in its claims (cited by claim id) and in its reports' named sections (cited by report id). */
    static Map<String, List<String>> namesOf(Shelf shelf, String subject, List<Finding> exclude) {
        Map<String, List<String>> out = new HashMap<>();
        for (Finding f : shelf.claims()) { if (exclude.contains(f)) continue; for (String n : namesIn(f, subject)) { List<String> l = out.computeIfAbsent(n, k -> new ArrayList<>()); if (!l.contains(f.id())) l.add(f.id()); } }
        for (var e : namesInReports(shelf.reports(), subject).entrySet()) { List<String> l = out.computeIfAbsent(e.getKey(), k -> new ArrayList<>()); for (String id : e.getValue()) if (!l.contains(id)) l.add(id); }
        return out;
    }

    /** The links of one subject: every name its claims and reports share with another dossier subject's. */
    public static List<Link> of(LibraryStore store, String subject) throws IOException {
        Map<String, Shelf> all = subjects(store);
        String key = null; for (String n : all.keySet()) if (n.equalsIgnoreCase(subject.strip())) key = n;
        Shelf mineShelf = key == null ? null : all.get(key);
        List<Finding> mine;
        if (mineShelf == null) {   // a subject with claims but no report yet: its claims by name
            mine = new ArrayList<>(); String low = subject.strip().toLowerCase(Locale.ROOT);
            for (Finding f : store.scanFindings().findings()) if ((f.title() + "\n" + f.body()).toLowerCase(Locale.ROOT).contains(low)) mine.add(f);
            key = subject.strip(); mineShelf = new Shelf(mine, List.of());
        } else mine = mineShelf.claims();
        Map<String, List<String>> myNames = namesOf(mineShelf, key, List.of());
        // a name most dossiers carry links nothing: when the shelves have four subjects or more, a name shared by more than half of them is left out
        Map<String, Integer> spread = new HashMap<>();
        if (all.size() >= 4) for (var e : all.entrySet()) for (String n : namesOf(e.getValue(), e.getKey(), List.of()).keySet()) spread.merge(n, 1, Integer::sum);
        List<Link> out = new ArrayList<>();
        for (var e : all.entrySet()) {
            String other = e.getKey();
            if (other.equalsIgnoreCase(key) || other.toLowerCase(Locale.ROOT).contains(key.toLowerCase(Locale.ROOT)) || key.toLowerCase(Locale.ROOT).contains(other.toLowerCase(Locale.ROOT))) continue;
            Map<String, List<String>> theirs = namesOf(e.getValue(), other, mine);
            for (var n : new TreeMap<>(myNames).entrySet()) {
                List<String> there = theirs.get(n.getKey());
                if (there == null) continue;
                if (n.getKey().equalsIgnoreCase(other) || other.toLowerCase(Locale.ROOT).contains(n.getKey().toLowerCase(Locale.ROOT))) continue;   // the other subject's own name is a relation the graph has, not a shared name
                if (all.size() >= 4 && spread.getOrDefault(n.getKey(), 0) * 2 > all.size()) continue;
                out.add(new Link(n.getKey(), other, dedupe(n.getValue()), dedupe(there)));
            }
        }
        return out;
    }

    /** The questions a subject's links raise, filed on the frontier when its report lands; the ones filed. */
    public static List<String> file(LibraryStore store, String subject) throws IOException {
        List<String> filed = new ArrayList<>();
        for (Link l : of(store, subject)) {
            String q = subject + " and " + l.other() + " both name " + l.name() + " (" + l.ownClaims().get(0) + ", " + l.otherClaims().get(0) + "): how are they connected?";
            if (Frontier.demand(store, q, "crosslinks " + subject)) filed.add(q);
        }
        return filed;
    }

    static List<String> dedupe(List<String> ids) { List<String> out = new ArrayList<>(); for (String s : ids) if (!out.contains(s)) out.add(s); return out; }
}
