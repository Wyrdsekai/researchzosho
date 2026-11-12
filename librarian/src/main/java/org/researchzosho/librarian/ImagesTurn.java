package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /**
     * The asks, in every language's words ({@link ChatWords}): "find (me) (some | 5) pictures of X" and "Xの画像を探して" ("pictures.find"),
     * "keep them" / "keep 1, 3" ("pictures.keep", "pictures.keep_all"), "which of these show X?" ("pictures.judge"), "describe this picture"
     * or "what is this?" with a picture attached ("pictures.describe"), "find more pictures of this person" ("pictures.same": the same one),
     * "find places like this" ("pictures.like": ones like it in kind, look and style). A noun in an ask is read as a kind of thing by
     * the words files' "kind." lists.
     */
    static Map<String, String> places() {
        return Map.of("what", ".+?", "n", "\\d{1,2}", "nums", "\\d{1,2}(?:\\s*(?:,|、|，|&|\\p{L}{1,3})\\s*\\d{1,2})*", "criteria", ".+?", "kind", ChatWords.alternation("kind."), "x", ".+?", "more", ".+?");
    }
    static Map<String, String> sameKind() { Map<String, String> p = new HashMap<>(places()); p.put("what", ChatWords.alternation("kind.")); return p; }

    /** What the turn says after the candidates. */
    static final String OFFER = "Say \"keep them\" to put them on the shelves, or \"keep 1, 3\" for some of them. \"Which of these show …?\" has the seeing model judge each against your words.";
    /** What a picture ask with no picture is told. */
    static final String ATTACH = "Attach the picture to the message and ask again. With a picture attached: \"find more pictures of this person\" looks for the same one; \"find places like this\" for ones like it; \"what is this?\" describes it.";

    /** Whether the words are an ask that wants a picture attached: more pictures of this, pictures like this, what is this. */
    /** Whether the words ask for pictures in any of this turn's forms: a search, a keep, a likeness, a description. */
    static boolean asksForPictures(String words) {
        if (words == null) return false;
        String w = TranslateTurn.withoutAttachment(words).strip();
        return wantsPicture(words) || find(w) != null || ChatWords.ask("pictures.keep", w, places()) != null || ChatWords.ask("pictures.keep_all", w, places()) != null;
    }
    static boolean wantsPicture(String words) {
        if (words == null) return false;
        String w = TranslateTurn.withoutAttachment(words).strip();
        return same(w) != null || ChatWords.ask("pictures.like", w, places()) != null || describes(w);
    }
    static Map<String, String> find(String w) { return ChatWords.ask("pictures.find", w, places()); }
    static Map<String, String> same(String w) { return ChatWords.ask("pictures.same", w, sameKind()); }
    /** Whether the words ask for the attached picture to be described: "describe this picture", "what is this?", "これは何", "qu'est-ce que c'est". */
    static boolean describes(String w) { return ChatWords.ask("pictures.describe", w, places()) != null; }
    /** What the turn says of an attached file it cannot read as a picture. */
    static final String GONE = "The attached file is not there any more; attach it again.", NOT_A_PICTURE = "The attached file is not a picture (JPEG, PNG, GIF, WebP, BMP or TIFF).",
            TOO_BIG = "The picture weighs more than 8 MB; the library reads pictures up to 8 MB.";
    /** When no engine answered a search. */
    static final String NO_ENGINE = "No picture search answered. The library searches pictures through Brave when its key is kept (the Settings page, under Sources and keys) and through its own SearXNG (researchzosho setup starts one).";

    /** The reply, or null when the words are none of this turn's. */
    static String reply(LibraryStore store, Librarian.Session session, Patrons.Patron patron, String words) throws IOException {
        if (words == null) return null;
        String w = TranslateTurn.withoutAttachment(words).strip();
        Map<String, String> m = find(w);
        if (m != null && m.get("what") != null) return found(store, session, m.get("what").strip(), count(m.get("n")));
        if (describes(w)) return described(store, session);
        Map<String, String> s = same(w);
        if (s != null) return liked(store, session, patron, Images.SAME, s.get("what") == null ? "thing" : kind(s.get("what")), s.get("more") == null ? "" : s.get("more").strip(), count(s.get("n")));
        Map<String, String> c = ChatWords.ask("pictures.like", w, places());
        if (c != null) return c.get("x") != null ? liked(store, session, patron, Images.CONCEPT, "look and style, showing " + c.get("x").strip(), c.get("x").strip(), Images.DEFAULT_COUNT)
                : liked(store, session, patron, Images.CONCEPT, aspect(c.get("kind")), "", count(c.get("n")));
        List<Images.Found> last = session == null ? List.of() : session.imagesFound();
        if (last.isEmpty()) return null;   // "keep them" and "which of these" mean the pictures of the last search; with none found they are not this turn's words
        Map<String, String> k = ChatWords.ask("pictures.keep", w, places());
        if (k != null) return kept(store, patron, last, numbers(k.get("nums")));
        if (ChatWords.ask("pictures.keep_all", w, places()) != null) return kept(store, patron, last, List.of());
        Map<String, String> j = ChatWords.ask("pictures.judge", w, places());
        if (j != null && j.get("criteria") != null) return judged(store, last, j.get("criteria").strip());
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
        Chrome c = Chrome.now();
        Images.Result r = Images.find(store, what, count);
        if (session != null) session.imagesFound(r.found());
        if (r.found().isEmpty()) return r.said(what, c) + (r.answered().isEmpty() ? " " + c.t(NO_ENGINE) : "");
        StringBuilder b = new StringBuilder(r.said(what, c)).append('\n');
        int i = 0;
        for (Images.Found f : r.found()) {
            i++;
            b.append('\n').append(i).append(". ").append(f.said(c)).append("\n   ").append(f.page() == null || f.page().isBlank() ? f.image() : f.page()).append("\n   ").append(f.show()).append('\n');
        }
        return b.append('\n').append(c.t(OFFER)).toString();
    }

    /** The pictures of the last search put on the shelves: all of them, or the ones numbered. */
    static String kept(LibraryStore store, Patrons.Patron patron, List<Images.Found> last, List<Integer> nums) {
        Chrome c = Chrome.now();
        if (!Patrons.mayWrite(store, patron)) return c.t("Only the person who keeps this library and those who may write to it put pictures on the shelves.");
        List<Images.Found> pick = new ArrayList<>();
        if (nums.isEmpty()) pick.addAll(last);
        else for (int n : nums) if (n >= 1 && n <= last.size() && !pick.contains(last.get(n - 1))) pick.add(last.get(n - 1));
        if (pick.isEmpty()) return c.t(last.size() == 1 ? "Which ones? The last search found {0} picture, numbered 1 to {0}." : "Which ones? The last search found {0} pictures, numbered 1 to {0}.", last.size());
        StringBuilder b = new StringBuilder();
        int kept = 0;
        for (Images.Found f : pick) {
            try {
                Images.Kept k = Images.keep(store, f, "", "");
                kept++;
                b.append(c.t("Kept {0} as {1}, shown at {2}.", f.title() == null || f.title().isBlank() ? f.image() : "\"" + f.title() + "\"", k.id(), k.url())).append('\n');
            } catch (IOException e) { b.append(c.t("Not kept: {0}", e.getMessage())).append('\n'); }
        }
        String head = kept == 0 ? c.t("None of them could be kept.") : c.t(kept == 1 ? "{0} picture is on the shelves now, each with the page it came from; researchzosho search finds them by their words." : "{0} pictures are on the shelves now, each with the page it came from; researchzosho search finds them by their words.", kept);
        return head + "\n" + b.toString().strip();
    }

    /** The pictures of the last search judged against the words, by the seeing model. */
    static String judged(LibraryStore store, List<Images.Found> last, String criteria) {
        Chrome c = Chrome.now();
        ModelEntries.Entry eyes = ModelEntries.seeing();
        if (eyes == null) return ModelEntries.noEyes(c);
        List<Images.Judged> js = Images.judge(store, eyes, criteria, last);
        int yes = 0; StringBuilder b = new StringBuilder();
        int i = 0;
        for (Images.Judged j : js) { i++; if (j.yes()) yes++; b.append('\n').append(i).append(". ").append(j.said(c)); }
        return c.t(yes == 1 ? "Judged against \"{0}\" by {1} ({2}): {3} of {4} shows it." : "Judged against \"{0}\" by {1} ({2}): {3} of {4} show it.", criteria, eyes.name(), eyes.model(), yes, js.size()) + b;
    }

    /** The thing a noun asks to match: a person, a place, an object, a thing. */
    static String kind(String noun) {
        String n = noun == null ? "" : noun.toLowerCase(Locale.ROOT);
        String named = ChatWords.whichOf("kind.", n);   // personne, Gebäude, 人物: a kind in any language's words
        return switch (n) {
            case "person", "man", "woman", "people", "face", "child", "boy", "girl", "人物", "人", "男性", "女性", "顔", "子ども" -> "person";
            case "place", "building", "house", "room", "church", "temple", "場所", "建物", "部屋" -> "place";
            case "one", "picture", "image", "photo", "thing", "item", "object", "物", "もの", "写真", "画像" -> "thing";
            case "animal", "dog", "cat", "bird", "horse", "動物", "犬", "猫", "鳥", "馬" -> "animal";
            case "像" -> "statue"; case "絵" -> "painting"; case "硬貨" -> "coin"; case "船" -> "ship";
            default -> named.isEmpty() ? n : named;
        };
    }

    /** A kind the turn worked with, in the person's words: person, place, thing and animal, and the few it names in English, through the chat's words; any other noun as it was said. */
    static String kindSaid(String kind, Chrome c) {
        return switch (kind == null ? "" : kind) { case "person", "place", "thing", "animal", "statue", "painting", "coin", "ship" -> c.t(kind); default -> kind == null ? "" : kind; };
    }

    /** An aspect to match, in the person's words: "the kind of place", "look and subject", "look and style, showing Athens". */
    static String aspectSaid(String aspect, Chrome c) {
        if (aspect == null) return "";
        if (aspect.startsWith("look and style, showing ")) return c.t("look and style, showing {0}", aspect.substring("look and style, showing ".length()));
        if (aspect.startsWith("the kind of ")) { String k = aspect.substring("the kind of ".length()); return c.t("the kind of {0}", kindSaid(k, c)); }
        return switch (aspect) { case "look and subject", "mood and look" -> c.t(aspect); default -> aspect; };
    }

    /** The aspect a kind of thing asks to be matched in: "the kind of place", "look and subject" for pictures. */
    static String aspect(String kind) {
        String k = kind == null ? "" : kind.toLowerCase(Locale.ROOT);
        if (ChatWords.whichOf("kind.", k).equals("picture")) return "look and subject";   // "pictures like this" in any language's words: картинки, Bilder, 图片
        return switch (k) {
            case "pictures", "images", "photos", "写真", "画像" -> "look and subject";
            case "things", "objects", "もの", "物" -> "the kind of thing";
            case "雰囲気" -> "mood and look";
            case "person", "place", "thing", "animal" -> "the kind of " + k;
            default -> !ChatWords.whichOf("kind.", k).isEmpty() ? "the kind of " + ChatWords.whichOf("kind.", k)   // lieux, Gebäude, 場所: a kind read from another language's words
                    : "the kind of " + (k.endsWith("ies") ? k.substring(0, k.length() - 3) + "y" : k.endsWith("es") && (k.endsWith("ches") || k.endsWith("shes") || k.endsWith("sses")) ? k.substring(0, k.length() - 2) : k.endsWith("s") ? k.substring(0, k.length() - 1) : k);
        };
    }

    /**
     * A picture as the ask: the attached picture kept on the shelves as the person's own, described by the seeing model, the candidates found
     * for the description's words and the person's, each judged against the picture itself when the model takes two pictures in one call,
     * else against the description; the reply says which mode and why, how the judging was done, and lists the candidates with their verdicts.
     */
    static String liked(LibraryStore store, Librarian.Session session, Patrons.Patron patron, String mode, String what, String words, int count) throws IOException {
        Chrome c = Chrome.now();
        if (session == null || session.att == null || !session.att.hasNonNull("path")) return c.t(ATTACH);
        Path p = Path.of(session.att.get("path").asText());
        if (!Files.isRegularFile(p)) return c.t(GONE);
        byte[] bytes = Files.readAllBytes(p);
        String name = session.att.path("name").asText(p.getFileName().toString());
        if (!Images.contentType(name).startsWith("image/") && Images.extension("", bytes) == null) return c.t(NOT_A_PICTURE);
        if (bytes.length > Images.MAX_BYTES) return c.t(TOO_BIG);
        ModelEntries.Entry eyes = ModelEntries.seeing();
        if (eyes == null) return ModelEntries.noEyes(c);
        String type = Images.contentType(name).startsWith("image/") ? Images.contentType(name) : "image/" + Images.extension("", bytes).replace("jpg", "jpeg").replace("tif", "tiff");
        Images.Kept kept = null;
        if (Patrons.mayWrite(store, patron)) { try { kept = Images.keepGiven(store, bytes, type, name, "", ""); } catch (IOException e) { kept = null; } }
        Images.Reference ref = Images.describeReference(eyes, bytes, type, kept);
        if (ref.description().isEmpty()) return c.t("The seeing model ({0}, {1}) gave no description of your picture, so there were no words to search by.", eyes.name(), eyes.model());
        Images.Liked liked = Images.like(store, eyes, ref, mode, what, words, count, true);
        if (session != null) session.imagesFound(liked.found().found());
        StringBuilder b = new StringBuilder();
        b.append(kept == null ? c.t("Your picture was read. {0} ({1}) described it: {2}", eyes.name(), eyes.model(), ref.said()) : c.t("Your picture is kept as {0} (shown at {1}). {2} ({3}) described it: {4}", kept.id(), kept.url(), eyes.name(), eyes.model(), ref.said())).append('\n');
        b.append(c.t("Searched for \"{0}\" ({1}): {2}", liked.words(), wordsWhy(liked, what, c), liked.found().said(liked.words(), c)))
         .append(liked.skipped() == 0 ? "" : " " + c.t(liked.skipped() == 1 ? "{0} more was skipped after the fetch as not pictures the model can look at." : "{0} more were skipped after the fetch as not pictures the model can look at.", liked.skipped())).append('\n');
        b.append(Images.CONCEPT.equals(liked.mode()) ? c.t("Looking for pictures like yours in {0}, because {1}.", aspectSaid(what, c), modeWhy(liked, what, c)) : c.t("Looking for the same {0} as in your picture, because {1}.", kindSaid(what, c), modeWhy(liked, what, c)))
         .append(' ').append(liked.how(eyes, c)).append('\n');
        if (liked.found().found().isEmpty()) return b.append(liked.found().answered().isEmpty() ? c.t(NO_ENGINE) : c.t("Try other words with the picture.")).toString();
        int i = 0;
        for (Images.Judged j : liked.judged()) {
            i++;
            b.append('\n').append(i).append(". ").append(j.said(c)).append("\n   ").append(j.found().page() == null || j.found().page().isBlank() ? j.found().image() : j.found().page()).append("\n   ").append(j.found().show()).append('\n');
        }
        b.append('\n').append(liked.tally("", c)).append(liked.appearsAlso(c).isEmpty() ? "" : " " + liked.appearsAlso(c)).append(' ').append(c.t(OFFER));
        return b.toString();
    }

    /** Why the search used the words it did, in the person's words: theirs, the description's, or the kind asked for. */
    static String wordsWhy(Images.Liked liked, String what, Chrome c) {
        String w = liked.wordsWhy();
        if (w.startsWith("your words, with ")) return c.t("your words, with \"{0}\" for a {1}", Images.kindWord(what), kindSaid(what == null ? "" : what.strip().toLowerCase(Locale.ROOT), c));
        return switch (w) { case "your words", "the description's words, since you gave none", "the kind of thing asked for, since the picture's description gave no words" -> c.t(w); default -> w; };
    }

    /** Why the search looked for the same one or for ones like it, in the person's words. */
    static String modeWhy(Images.Liked liked, String what, Chrome c) {
        if (Images.CONCEPT.equals(liked.mode())) return c.t("the words named an aspect to match, {0}", aspectSaid(what, c));
        return what == null || what.isBlank() ? c.t("the words asked for more of what the picture shows") : c.t("the words asked for more pictures of this {0}, so the same one is looked for", kindSaid(what, c));
    }

    /** The picture attached to this turn, described by the seeing model. */
    static String described(LibraryStore store, Librarian.Session session) throws IOException {
        Chrome c = Chrome.now();
        if (session == null || session.att == null || !session.att.hasNonNull("path")) return c.t(ATTACH);
        Path p = Path.of(session.att.get("path").asText());
        if (!Files.isRegularFile(p)) return c.t(GONE);
        byte[] bytes = Files.readAllBytes(p);
        String name = session.att.path("name").asText(p.getFileName().toString());
        if (!Images.contentType(name).startsWith("image/") && Images.extension("", bytes) == null) return c.t(NOT_A_PICTURE);
        if (bytes.length > Images.MAX_BYTES) return c.t(TOO_BIG);
        ModelEntries.Entry eyes = ModelEntries.seeing();
        if (eyes == null) return ModelEntries.noEyes(c);
        String type = Images.contentType(name).startsWith("image/") ? Images.contentType(name) : "image/" + Images.extension("", bytes).replace("jpg", "jpeg").replace("tif", "tiff");
        String d = Images.describe(eyes, bytes, type.toLowerCase(Locale.ROOT), "");
        return d.isEmpty() ? c.t("The seeing model ({0}, {1}) gave no description of it.", eyes.name(), eyes.model()) : d + "\n\n(" + eyes.name() + ", " + eyes.model() + ")";
    }
}
