package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Find me some pictures of the Antikythera mechanism", said in the chat: the library's own turn, no model asked. The candidates come
 * back numbered, each with its page and a small picture; "keep them" (or "keep 1, 3") puts them on the shelves; "which of these show a
 * bronze gear?" has the seeing model judge them; "describe this picture" with a picture attached has it described. When an ask needs
 * the seeing model and none is set, the answer says what the library has and how one is added ({@link ModelEntries#noEyes()}).
 */
final class ImagesTurn {
    private ImagesTurn() { }

    /** "find (me) (some | a few | 5) images/pictures/photos of X", "show me pictures of X", "search for photos of X", "look for images showing X". */
    static final Pattern FIND = Pattern.compile("(?i)^\\s*(?:please\\s+|can you\\s+|could you\\s+)?(?:find|show|get|fetch|search(?:\\s+for)?|look(?:\\s+for|\\s+up)?)\\s+(?:me\\s+)?(?:some\\s+|a few\\s+|(?<n>\\d{1,2})\\s+)?(?:images|pictures|photos|photographs|pics)\\s+(?:of|for|showing|about)\\s+(?<what>.+?)\\s*[.!?]?\\s*$");
    /** "Xの画像を探して", "Xの写真を5枚見せて". */
    static final Pattern FIND_JA = Pattern.compile("^\\s*(?<what>.+?)の(?:画像|写真)を(?:(?<n>\\d{1,2})(?:枚|件)?)?(?:探して|見せて|検索して)(?:ください)?\\s*$");
    /** "keep them", "keep those", "save the pictures", "keep all", "keep 1, 3". */
    static final Pattern KEEP = Pattern.compile("(?i)^\\s*(?:please\\s+|yes,?\\s+)?(?:keep|save|shelve)\\s+(?:them|those|these|all(?:\\s+of\\s+them)?|(?:the\\s+)?(?:pictures|images|photos)|(?<nums>\\d{1,2}(?:\\s*(?:,|and|&)\\s*\\d{1,2})*))\\s*[.!]?\\s*$");
    static final Pattern KEEP_JA = Pattern.compile("^\\s*(?:それら|これら|全部|すべて|その(?:画像|写真))を(?:保存|保管|棚に入れて)(?:して)?(?:ください)?\\s*$");
    /** "which of these show X?", "do these pictures show X?", "do any of them show X" after a find. */
    static final Pattern JUDGE = Pattern.compile("(?i)^\\s*(?:which\\s+of\\s+(?:these|those|them)|do\\s+(?:these|those|they|any\\s+of\\s+(?:these|those|them))(?:\\s+(?:pictures|images|photos))?)\\s+(?:show|have|contain|depict|are\\s+of)\\s+(?<criteria>.+?)\\s*\\??\\s*$");
    /** "describe this picture", "what is in this photo?", "what does the attached image show?", "what is this?", "who is this?", with a picture attached. */
    static final Pattern DESCRIBE = Pattern.compile("(?i)^\\s*(?:please\\s+)?(?:describe\\s+(?:this|the|that|the attached|attached)\\s+(?:picture|image|photo|photograph)|what(?:'s|\\s+is)\\s+(?:in|on)\\s+(?:this|the|that|the attached)\\s+(?:picture|image|photo|photograph)|what\\s+does\\s+(?:this|the|that|the attached)\\s+(?:picture|image|photo|photograph)\\s+show"
            + "|what(?:'s|\\s+is)\\s+this(?:\\s+(?:picture|image|photo|photograph|a picture of))?|who(?:'s|\\s+is)\\s+(?:this|that|the person in (?:this|the) (?:picture|photo|image))|what\\s+does\\s+this\\s+show)\\s*[.?!]?\\s*$"
            + "|^\\s*(?:この|その|添付の)(?:画像|写真)を説明して(?:ください)?\\s*$|^\\s*これは(?:何|誰|なん|だれ)(?:ですか|でしょう|\\?|？)?\\s*$");

    /** A picture as the ask, the same thing: "find more pictures of this person", "show me other photos of this building", "find 5 more pictures of this man in Athens"; the noun says what to match. */
    static final Pattern LIKE_SAME = Pattern.compile("(?i)^\\s*(?:please\\s+|can you\\s+|could you\\s+)?(?:find|show|get|fetch|search(?:\\s+for)?|look(?:\\s+for|\\s+up)?)\\s+(?:me\\s+)?(?:(?<n>\\d{1,2})\\s+)?(?:more|other|similar|additional|further)?\\s*(?:images|pictures|photos|photographs|pics)\\s+(?:of|showing)\\s+(?:this|that|the|the same)\\s+(?<what>person|man|woman|people|face|child|boy|girl|object|thing|item|place|building|house|room|church|temple|statue|painting|coin|vase|ship|car|animal|dog|cat|bird|horse|one|picture|image|photo)(?:\\s+(?<more>.+?))?\\s*[.!?]?\\s*$"
            + "|(?i)^\\s*(?:find|show|search for|look for)\\s+(?:me\\s+)?(?:more|others?)\\s+like\\s+(?:this|that|it)(?:\\s+(?:one|picture|image|photo))?\\s*[.!?]?\\s*$");
    /** The same, in Japanese: "この人の写真をもっと探して", "この建物の他の画像を探して". */
    static final Pattern LIKE_SAME_JA = Pattern.compile("^\\s*この(?<what>人物|人|男性|女性|顔|子ども|物|もの|建物|場所|部屋|像|絵|硬貨|船|動物|犬|猫|鳥|馬|写真|画像)の(?:他の|別の|ほかの)?(?:画像|写真)を(?:(?<n>\\d{1,2})(?:枚|件)?)?(?:もっと)?(?:探して|見せて|検索して)(?:ください)?\\s*$");
    /** A picture as a concept: "find places like this", "show me rooms like this one" — like it in kind, look and style, not the same one. */
    static final Pattern LIKE_CONCEPT = Pattern.compile("(?i)^\\s*(?:please\\s+)?(?:find|show|search for|look for)\\s+(?:me\\s+)?(?:(?<n>\\d{1,2})\\s+)?(?<kind>places|pictures|images|photos|things|buildings|houses|rooms|interiors|landscapes|scenes|gardens|paintings|designs|dresses|objects|cities|towns|streets|beaches|mountains)\\s+like\\s+(?:this|that)(?:\\s+(?:one|picture|image|photo))?\\s*[.!?]?\\s*$"
            + "|(?i)^\\s*use\\s+(?:this|that|the attached|the)\\s*(?:picture|image|photo)?\\s+as\\s+(?:the|a|my)\\s+reference\\s+for\\s+(?:pictures|images|photos)\\s+of\\s+(?<x>.+?)\\s*[.!?]?\\s*$");
    /** The same, in Japanese: "この写真と似た場所を探して", "この写真を参考に東京の画像を探して". */
    static final Pattern LIKE_CONCEPT_JA = Pattern.compile("^\\s*この(?:写真|画像)と(?:似た|似ている|同じような)(?<kind>場所|建物|部屋|風景|景色|庭|絵|もの|物|写真|画像|雰囲気|街|通り)を(?:探して|見せて|検索して)(?:ください)?\\s*$|^\\s*この(?:写真|画像)を参考に(?<x>.+?)の(?:画像|写真)を(?:探して|見せて)(?:ください)?\\s*$");

    /** What the turn says after the candidates. */
    static final String OFFER = "Say \"keep them\" to put them on the shelves, or \"keep 1, 3\" for some of them. \"Which of these show …?\" has the seeing model judge each against your words.";
    /** What a picture ask with no picture is told. */
    static final String ATTACH = "Attach the picture to the message and ask again. With a picture attached: \"find more pictures of this person\" looks for the same one; \"find places like this\" for ones like it; \"what is this?\" describes it.";

    /** Whether the words are an ask that wants a picture attached: more pictures of this, pictures like this, what is this. */
    /** Whether the words ask for pictures in any of this turn's forms: a search, a keep, a likeness, a description. */
    static boolean asksForPictures(String words) {
        if (words == null) return false;
        String w = TranslateTurn.withoutAttachment(words).strip();
        return wantsPicture(words) || FIND.matcher(w).matches() || FIND_JA.matcher(w).matches() || KEEP.matcher(w).matches() || KEEP_JA.matcher(w).matches();
    }
    static boolean wantsPicture(String words) {
        if (words == null) return false;
        String w = TranslateTurn.withoutAttachment(words).strip();
        return LIKE_SAME.matcher(w).matches() || LIKE_SAME_JA.matcher(w).matches() || LIKE_CONCEPT.matcher(w).matches() || LIKE_CONCEPT_JA.matcher(w).matches() || DESCRIBE.matcher(w).matches();
    }
    /** When no engine answered a search. */
    static final String NO_ENGINE = "No picture search answered. The library searches pictures through Brave when its key is kept (the Settings page, under Sources and keys) and through its own SearXNG (researchzosho setup starts one).";

    /** The reply, or null when the words are none of this turn's. */
    static String reply(LibraryStore store, Librarian.Session session, Patrons.Patron patron, String words) throws IOException {
        if (words == null) return null;
        String w = TranslateTurn.withoutAttachment(words).strip();
        Matcher m = FIND.matcher(w);
        if (!m.matches()) { m = FIND_JA.matcher(w); if (!m.matches()) m = null; }
        if (m != null) return found(store, session, m.group("what").strip(), count(m.group("n")));
        if (DESCRIBE.matcher(w).matches()) return described(store, session);
        Matcher s = LIKE_SAME.matcher(w);
        if (s.matches()) return liked(store, session, patron, Images.SAME, s.group("what") == null ? "thing" : kind(s.group("what")), s.group("more") == null ? "" : s.group("more").strip(), count(s.group("n")));
        Matcher sj = LIKE_SAME_JA.matcher(w);
        if (sj.matches()) return liked(store, session, patron, Images.SAME, kind(sj.group("what")), "", count(sj.group("n")));
        Matcher c = LIKE_CONCEPT.matcher(w);
        if (c.matches()) return c.group("x") != null ? liked(store, session, patron, Images.CONCEPT, "look and style, showing " + c.group("x").strip(), c.group("x").strip(), Images.DEFAULT_COUNT)
                : liked(store, session, patron, Images.CONCEPT, aspect(c.group("kind")), "", count(c.group("n")));
        Matcher cj = LIKE_CONCEPT_JA.matcher(w);
        if (cj.matches()) return cj.group("x") != null ? liked(store, session, patron, Images.CONCEPT, "look and style, showing " + cj.group("x").strip(), cj.group("x").strip(), Images.DEFAULT_COUNT)
                : liked(store, session, patron, Images.CONCEPT, aspect(cj.group("kind")), "", Images.DEFAULT_COUNT);
        List<Images.Found> last = session == null ? List.of() : session.imagesFound();
        if (last.isEmpty()) return null;   // "keep them" and "which of these" mean the pictures of the last search; with none found they are not this turn's words
        Matcher k = KEEP.matcher(w);
        if (k.matches()) return kept(store, patron, last, numbers(k.group("nums")));
        if (KEEP_JA.matcher(w).matches()) return kept(store, patron, last, List.of());
        Matcher j = JUDGE.matcher(w);
        if (j.matches()) return judged(store, last, j.group("criteria").strip());
        return null;
    }

    static int count(String n) {
        if (n == null || n.isBlank()) return Images.DEFAULT_COUNT;
        try { return Math.min(Math.max(Integer.parseInt(n), 1), Images.MOST); } catch (NumberFormatException e) { return Images.DEFAULT_COUNT; }
    }

    /** "1, 3 and 4" → [1, 3, 4]; empty for none. */
    static List<Integer> numbers(String s) {
        List<Integer> out = new ArrayList<>();
        if (s == null) return out;
        Matcher m = Pattern.compile("\\d{1,2}").matcher(s);
        while (m.find()) out.add(Integer.parseInt(m.group()));
        return out;
    }

    /** The search, its candidates numbered with their pages and small pictures, and the offer; the candidates kept beside the session. */
    static String found(LibraryStore store, Librarian.Session session, String what, int count) throws IOException {
        Images.Result r = Images.find(store, what, count);
        if (session != null) session.imagesFound(r.found());
        if (r.found().isEmpty()) return r.said(what) + (r.answered().isEmpty() ? " " + NO_ENGINE : "");
        StringBuilder b = new StringBuilder(r.said(what)).append('\n');
        int i = 0;
        for (Images.Found f : r.found()) {
            i++;
            b.append('\n').append(i).append(". ").append(f.said()).append("\n   ").append(f.page() == null || f.page().isBlank() ? f.image() : f.page()).append("\n   ").append(f.show()).append('\n');
        }
        return b.append('\n').append(OFFER).toString();
    }

    /** The pictures of the last search put on the shelves: all of them, or the ones numbered. */
    static String kept(LibraryStore store, Patrons.Patron patron, List<Images.Found> last, List<Integer> nums) {
        if (!Patrons.mayWrite(store, patron)) return "Only the person who keeps this library and those who may write to it put pictures on the shelves.";
        List<Images.Found> pick = new ArrayList<>();
        if (nums.isEmpty()) pick.addAll(last);
        else for (int n : nums) if (n >= 1 && n <= last.size() && !pick.contains(last.get(n - 1))) pick.add(last.get(n - 1));
        if (pick.isEmpty()) return "Which ones? The last search found " + last.size() + (last.size() == 1 ? " picture" : " pictures") + ", numbered 1 to " + last.size() + ".";
        StringBuilder b = new StringBuilder();
        int kept = 0;
        for (Images.Found f : pick) {
            try {
                Images.Kept k = Images.keep(store, f, "", "");
                kept++;
                b.append("Kept ").append(f.title() == null || f.title().isBlank() ? f.image() : "\"" + f.title() + "\"").append(" as ").append(k.id()).append(", shown at ").append(k.url()).append(".\n");
            } catch (IOException e) { b.append("Not kept: ").append(e.getMessage()).append('\n'); }
        }
        String head = kept == 0 ? "None of them could be kept." : kept + (kept == 1 ? " picture is" : " pictures are") + " on the shelves now, each with the page it came from; researchzosho search finds them by their words.";
        return head + "\n" + b.toString().strip();
    }

    /** The pictures of the last search judged against the words, by the seeing model. */
    static String judged(LibraryStore store, List<Images.Found> last, String criteria) {
        ModelEntries.Entry eyes = ModelEntries.seeing();
        if (eyes == null) return ModelEntries.noEyes();
        List<Images.Judged> js = Images.judge(store, eyes, criteria, last);
        int yes = 0; StringBuilder b = new StringBuilder();
        int i = 0;
        for (Images.Judged j : js) { i++; if (j.yes()) yes++; b.append('\n').append(i).append(". ").append(j.said()); }
        return "Judged against \"" + criteria + "\" by " + eyes.name() + " (" + eyes.model() + "): " + yes + " of " + js.size() + (yes == 1 ? " shows" : " show") + " it." + b;
    }

    /** The thing a noun asks to match: a person, a place, an object, a thing. */
    static String kind(String noun) {
        String n = noun == null ? "" : noun.toLowerCase(Locale.ROOT);
        return switch (n) {
            case "person", "man", "woman", "people", "face", "child", "boy", "girl", "人物", "人", "男性", "女性", "顔", "子ども" -> "person";
            case "place", "building", "house", "room", "church", "temple", "場所", "建物", "部屋" -> "place";
            case "one", "picture", "image", "photo", "thing", "item", "object", "物", "もの", "写真", "画像" -> "thing";
            case "animal", "dog", "cat", "bird", "horse", "動物", "犬", "猫", "鳥", "馬" -> "animal";
            case "像" -> "statue"; case "絵" -> "painting"; case "硬貨" -> "coin"; case "船" -> "ship";
            default -> n;
        };
    }

    /** The aspect a kind of thing asks to be matched in: "the kind of place", "look and subject" for pictures. */
    static String aspect(String kind) {
        String k = kind == null ? "" : kind.toLowerCase(Locale.ROOT);
        return switch (k) {
            case "pictures", "images", "photos", "写真", "画像" -> "look and subject";
            case "things", "objects", "もの", "物" -> "the kind of thing";
            case "雰囲気" -> "mood and look";
            default -> "the kind of " + (k.endsWith("ies") ? k.substring(0, k.length() - 3) + "y" : k.endsWith("es") && (k.endsWith("ches") || k.endsWith("shes") || k.endsWith("sses")) ? k.substring(0, k.length() - 2) : k.endsWith("s") ? k.substring(0, k.length() - 1) : k);
        };
    }

    /**
     * A picture as the ask: the attached picture kept on the shelves as the person's own, described by the seeing model, the candidates found
     * for the description's words and the person's, each judged against the picture itself when the model takes two pictures in one call,
     * else against the description; the reply says which mode and why, how the judging was done, and lists the candidates with their verdicts.
     */
    static String liked(LibraryStore store, Librarian.Session session, Patrons.Patron patron, String mode, String what, String words, int count) throws IOException {
        if (session == null || session.att == null || !session.att.hasNonNull("path")) return ATTACH;
        Path p = Path.of(session.att.get("path").asText());
        if (!Files.isRegularFile(p)) return "The attached file is not there any more; attach it again.";
        byte[] bytes = Files.readAllBytes(p);
        String name = session.att.path("name").asText(p.getFileName().toString());
        if (!Images.contentType(name).startsWith("image/") && Images.extension("", bytes) == null) return "The attached file is not a picture (JPEG, PNG, GIF, WebP, BMP or TIFF).";
        if (bytes.length > Images.MAX_BYTES) return "The picture weighs more than 8 MB; the library reads pictures up to 8 MB.";
        ModelEntries.Entry eyes = ModelEntries.seeing();
        if (eyes == null) return ModelEntries.noEyes();
        String type = Images.contentType(name).startsWith("image/") ? Images.contentType(name) : "image/" + Images.extension("", bytes).replace("jpg", "jpeg").replace("tif", "tiff");
        Images.Kept kept = null;
        if (Patrons.mayWrite(store, patron)) { try { kept = Images.keepGiven(store, bytes, type, name, "", ""); } catch (IOException e) { kept = null; } }
        Images.Reference ref = Images.describeReference(eyes, bytes, type, kept);
        if (ref.description().isEmpty()) return "The seeing model (" + eyes.name() + ", " + eyes.model() + ") gave no description of your picture, so there were no words to search by.";
        Images.Liked liked = Images.like(store, eyes, ref, mode, what, words, count, true);
        if (session != null) session.imagesFound(liked.found().found());
        StringBuilder b = new StringBuilder();
        b.append(kept == null ? "Your picture was read" : "Your picture is kept as " + kept.id() + " (shown at " + kept.url() + ")").append(". ").append(eyes.name()).append(" (").append(eyes.model()).append(") described it: ").append(ref.said()).append('\n');
        b.append("Searched for \"").append(liked.words()).append("\" (").append(liked.wordsWhy()).append("): ").append(liked.found().said(liked.words())).append(liked.skipped() == 0 ? "" : " " + liked.skipped() + (liked.skipped() == 1 ? " more was" : " more were") + " skipped after the fetch as not pictures the model can look at.").append('\n');
        b.append(Images.CONCEPT.equals(liked.mode()) ? "Looking for pictures like yours in " + what : "Looking for the same " + what + " as in your picture").append(", because ").append(liked.modeWhy()).append(". ").append(liked.how(eyes)).append('\n');
        if (liked.found().found().isEmpty()) return b.append(liked.found().answered().isEmpty() ? NO_ENGINE : "Try other words with the picture.").toString();
        int i = 0;
        for (Images.Judged j : liked.judged()) {
            i++;
            b.append('\n').append(i).append(". ").append(j.said()).append("\n   ").append(j.found().page() == null || j.found().page().isBlank() ? j.found().image() : j.found().page()).append("\n   ").append(j.found().show()).append('\n');
        }
        b.append('\n').append(liked.tally("")).append(liked.appearsAlso().isEmpty() ? "" : " " + liked.appearsAlso()).append(' ').append(OFFER);
        return b.toString();
    }

    /** The picture attached to this turn, described by the seeing model. */
    static String described(LibraryStore store, Librarian.Session session) throws IOException {
        if (session == null || session.att == null || !session.att.hasNonNull("path")) return ATTACH;
        Path p = Path.of(session.att.get("path").asText());
        if (!Files.isRegularFile(p)) return "The attached file is not there any more; attach it again.";
        byte[] bytes = Files.readAllBytes(p);
        String name = session.att.path("name").asText(p.getFileName().toString());
        if (!Images.contentType(name).startsWith("image/") && Images.extension("", bytes) == null) return "The attached file is not a picture (JPEG, PNG, GIF, WebP, BMP or TIFF).";
        if (bytes.length > Images.MAX_BYTES) return "The picture weighs more than 8 MB; the library reads pictures up to 8 MB.";
        ModelEntries.Entry eyes = ModelEntries.seeing();
        if (eyes == null) return ModelEntries.noEyes();
        String type = Images.contentType(name).startsWith("image/") ? Images.contentType(name) : "image/" + Images.extension("", bytes).replace("jpg", "jpeg").replace("tif", "tiff");
        String d = Images.describe(eyes, bytes, type.toLowerCase(Locale.ROOT), "");
        return d.isEmpty() ? "The seeing model (" + eyes.name() + ", " + eyes.model() + ") gave no description of it." : d + "\n\n(" + eyes.name() + ", " + eyes.model() + ")";
    }
}
