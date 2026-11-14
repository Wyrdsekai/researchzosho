package org.researchzosho.librarian;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a comic's translation leaves in words beside its pages: the note, and the volume's script as its entry on the shelves.
 *
 * <p>The note says what any translation's note says (the tier, the models, the checks, what they still doubt, the names and terms
 * decided, the shelves) and what is a comic's own: who read the pages and what was found on them, how the pages reached the translator,
 * the language the lettering was read in and how that was known, what was lettered and at what sizes, which lines went back to be
 * said shorter for their balloon and what they became, and for every page a table of its blocks: the lettering as read (for Japanese,
 * with the readings printed beside it), the translation, how it was set and whether in columns, a sound word's gloss, a text outside a
 * balloon with its translation, and why a person should look. A piece of a web comic's strip is a page of its own here, named by the
 * strip and its place in it ("page 3, part 2 of 7"). It ends with what this version does not do, said once. In English, as every
 * translation note is.
 */
final class ComicNote {
    private ComicNote() { }

    /**
     * The volume as a script, page by page and block by block: the translation's lines when {@code translated} (a sound word with its
     * gloss, a text outside a balloon with its translation, each marked as what it is), else the lettering as read, a Japanese comic's
     * readings in parentheses. {@code labels}: each page as a person names it ("3", or "3, part 2 of 7" for a piece of a strip). What
     * the shelves hold of a comic and what a search of the library finds; the archive is its file.
     */
    static String script(String title, List<List<ComicTranslation.Row>> rows, List<String> labels, boolean translated) {
        StringBuilder b = new StringBuilder("# " + title + "\n");
        for (int p = 0; p < rows.size(); p++) {
            StringBuilder page = new StringBuilder();
            for (ComicTranslation.Row r : rows.get(p)) {
                String text = translated ? r.translation() : r.block().shown();
                if (text == null || text.isBlank()) continue;
                String mark = r.block().kind().equals(ComicTranslation.SOUND) ? "(sound) " : r.block().kind().equals(ComicTranslation.OUTSIDE) ? "(outside a balloon) " : "";
                page.append(r.block().number()).append(". ").append(mark).append(text.replaceAll("\\s+", " ").strip()).append('\n');
            }
            if (page.length() > 0) b.append("\n## Page ").append(labels.get(p)).append("\n\n").append(page);
        }
        return b.toString();
    }

    private static String plural(int n, String one, String many) { return n + " " + (n == 1 ? one : many); }

    /** Where a unit stands: "page 4, block 2"; in a strip's piece, "page 3, part 2 of 7, block 2". */
    private static String at(ComicTranslation.Done d, int unit) { ComicTranslation.Block b = d.units().get(unit); return d.pageSaid(b.page()) + ", block " + b.number(); }

    /** The helper's reader's confidence in a block as a table holds it: two decimals, nothing where the helper gave none. */
    private static String sureSaid(double sure) { return sure < 0 ? "" : String.format(Locale.ROOT, "%.2f", sure); }

    /** A cell's text, cut where a model ran on: a gloss of a sound word that repeats one word hundreds of times is said by its first 300 characters and its length. */
    private static String clipped(String s) { return s.length() <= 300 ? s : s.substring(0, 300) + " … (" + s.length() + " characters in all)"; }

    /** A share as a note says it: 0.8 is 80%. */
    private static String percent(double share) { return Math.round(share * 100) + "%"; }

    /** The writing system the helper said a sample was lettered in, in a few words. */
    private static String writing(String script) {
        return switch (script) { case "japanese" -> "kana among the characters"; case "korean" -> "hangul"; case "chinese" -> "Chinese characters and no kana"; case "latin" -> "the Latin alphabet"; case "cyrillic" -> "the Cyrillic alphabet"; case "greek" -> "the Greek alphabet"; default -> script; };
    }

    /** How the language of the lettering was known, after its name: the person named it; or what the helper found on the pages it looked at, and for an alphabet that the words read say which language, clearly or not. */
    static String sourceHow(ComicTranslation.Source s) {
        if (s.how().equals(ComicTranslation.BY_PERSON)) return ", the language named";
        String looked = "the comic helper looked at " + plural(s.sampled(), "page", "pages") + " and found " + writing(s.script());
        boolean alphabet = s.script().equals("latin") || s.script().equals("cyrillic");
        return ": " + looked + (!alphabet ? "" : s.other().isEmpty() ? ", and the words read there say " + ReadIn.name(s.code()) : ", and the words read there say " + ReadIn.name(s.code()) + " without saying it clearly; it could be " + ReadIn.name(s.other()) + ", and naming the language (--from) settles it");
    }

    static String note(Translation.Outcome o, Path in, Translation.Plan plan) {
        Chrome c = Chrome.ENGLISH;
        ComicTranslation.Done d = o.comic();
        String name = ReadIn.name(o.to());
        boolean quick = o.quality().equals(Translation.QUICK);
        StringBuilder b = new StringBuilder("# Translation note: \"" + o.title() + "\" into " + name + "\n\n");
        if (o.directed()) b.append("- Direction, as given: \"").append(o.direction()).append("\" (told to the translator, the reviser and the reader; it changes the voice, never the numbers, the names and the glossary terms, which the program checks as in any run)\n");
        String source = ReadIn.name(o.from());
        boolean japanese = o.from().equals("ja"), columnsTarget = ComicTranslation.IN_COLUMNS.contains(o.to());
        b.append("- From: ").append(in.toAbsolutePath().normalize()).append(" (").append(d.container()).append(", a comic of ").append(plural(d.pages(), "page", "pages"))
                .append(d.strips() == 0 ? "" : ", " + d.stripsSaid() + ", cut into " + plural(d.pieces(), "piece", "pieces") + " to be read and lettered, and joined again")
                .append("; its lettering read as ").append(source).append(sourceHow(d.source()))
                .append("; its pages read from the ").append(d.reads().rtl() ? "right to the left" : "left to the right").append(switch (d.reads().how()) {   // the way the pages were read, and how that was known: said, or found
                    case ComicTranslation.READS_SAID -> ", as the person said";
                    case ComicTranslation.READS_MANGA -> ", the person calling it a manga";
                    default -> japanese ? ", as a Japanese comic's are" : o.from().equals("zh") ? (d.reads().rtl() ? ", its lettering running in columns" : ", its lettering running in lines") : ", as " + source + " is read";
                })
                .append(switch (d.taken()) { case ComicTranslation.SAID -> "; a comic on the person's word"; case ComicTranslation.FOUND -> "; taken for a comic because the comic helper found speech balloons on its pages"; default -> ""; }).append(")\n");
        b.append("- Pages read by: the comic helper on ").append(d.helperOn().isEmpty() ? "this machine" : d.helperOn()).append(d.helper().isEmpty() ? "" : " (" + d.helper() + ")").append(". Found: ").append(plural(d.speech(), "speech balloon", "speech balloons")).append(japanese ? ", " : " ")
                .append(japanese ? plural(d.outside(), "text outside a balloon", "texts outside a balloon") + ", " + plural(d.sound(), "drawn sound word", "drawn sound words")
                        : "and " + plural(d.outside() + d.sound(), "text outside a balloon", "texts outside a balloon") + " (signs, captions on the art, drawn sound words)")   // only a Japanese comic's sound words are told from its other lettering on the art with any care
                .append(japanese ? ", " + plural(d.readings(), "reading printed beside a word", "readings printed beside words") : "").append(".")   // readings are printed beside words in Japanese alone
                .append(d.unread().isEmpty() ? "" : " Could not be read, and stand as they were: " + String.join("; ", d.unread()) + ".").append("\n");
        ComicTranslation.Twice twice = d.twice();
        if (twice.made()) {   // the lettering read a second time: who, how many blocks, what was taken, what differs, and the rule an answer is taken by
            b.append("- Lettering read a second time: by ").append(o.translatorName()).append(", the translator, each speech block from a picture of it that the helper cut out, a block a call. Blocks read twice: ").append(twice.asked())
                    .append("; readings taken as the block's text: ").append(twice.taken()).append("; readings that differ from the comic helper's in any character (a mark, a space or a capital counts): ").append(twice.differed()).append("; in a letter or a digit: ").append(twice.inLetters()).append(twice.inLetters() == 0 ? "" : " (listed under Readings in doubt below)");
            if (!twice.notTaken().isEmpty()) { b.append("; answers not taken: "); int k = 0; for (var e : twice.notTaken().entrySet()) b.append(k++ == 0 ? "" : ", ").append(e.getValue()).append(" (").append(e.getKey()).append(")"); }
            b.append(". ").append(ComicTranslation.SECOND_RULE);
            if (!twice.notCut().isEmpty()) b.append(" The helper could not cut the blocks out of ").append(String.join("; ", twice.notCut())).append(": there the lettering stands as the helper read it.");
            b.append("\n");
        } else if (twice.on()) b.append("- Lettering read once, by the comic helper: it was to be read a second time by the translator, and ")   // a run that was to read twice and read nothing twice says why
                .append(twice.notCut().isEmpty() ? "no speech block was there to read" : "the helper could not cut the blocks out of " + String.join("; ", twice.notCut())).append(".\n");
        else if (!japanese) b.append("- Lettering read once, by the comic helper: ").append(twice.how().equals(ComicTranslation.ONCE_QUICK) ? "the quick tier has no second reading of it" : "the translator does not see pictures, so it could not read the balloons a second time").append(".\n");
        if (d.strips() > 0) {   // the strips: how many pictures were cut, into how many pieces, and what the cuts run through, as the helper says it
            int cuts = 0; for (int n : d.cuts().values()) cuts += n;
            b.append("- Strips: ").append(plural(d.strips(), "picture is a web comic's strip", "pictures are web comic strips")).append(" (more than 2.2 times as high as wide), cut into ").append(plural(d.pieces(), "piece", "pieces")).append(", each read, translated and lettered as a page and then joined again");
            if (cuts > 0) {
                List<String> through = new ArrayList<>();
                for (var e : d.cuts().entrySet()) through.add(e.getValue() + switch (e.getKey()) { case "gutter" -> " in a gutter between panels"; case "art" -> " through art"; case "balloon" -> " through a balloon"; case "lettering" -> " through lettering"; default -> " through " + e.getKey(); });
                b.append(". Of the ").append(plural(cuts, "cut", "cuts")).append(": ").append(String.join(", ", through));
                if (d.cuts().containsKey("balloon") || d.cuts().containsKey("lettering")) b.append(". A balloon or a line that a cut runs through stands in two pieces, each read and lettered on its own: look at those places");
            }
            b.append(".\n");
        }
        if (!d.leftOut().isEmpty()) b.append("- Left out of the volume: ").append(String.join("; ", d.leftOut())).append("\n");
        b.append("- To: ").append(o.output().toAbsolutePath().normalize()).append(" (").append(o.format()).append(", ").append(name).append("; each page in the format it came in)\n");
        b.append("- When: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append(", in ").append(Translation.duration(o.seconds())).append(" (").append(o.seconds()).append(" s, ")
                .append(String.format(Locale.ROOT, "%.1f", o.seconds() / (double) Math.max(1, d.pages()))).append(" s a page)\n");
        b.append("- Tier: ").append(o.quality()).append(switch (o.quality()) {
            case Translation.QUICK -> " (the pages read, one call a page, the lines set into the balloons)";
            case Translation.REVIEWED, Translation.THOROUGH -> " (one call a page, the program's checks, a revision of what they flagged" + (o.backTranslated() > 0 ? ", a back-translation sample" : "") + ", a second reading of " + o.readSaid() + ", a revision of what it flagged major or critical" + (o.directed() ? " or as not following the direction" : "") + ", the fit of each line to its balloon)";
            default -> " (the default: one call a page, the program's checks, a revision of what they flagged" + (o.backTranslated() > 0 ? ", a back-translation sample" : "") + ", the fit of each line to its balloon; no second model)";
        }).append("\n");
        b.append("- Models: ").append(o.translatorName()).append(o.translatorName().equals(o.translatorModel()) ? "" : " (" + o.translatorModel() + ")").append(" translating, ")
                .append(d.pictures() ? "each page given to it as a picture with its blocks boxed and numbered, beside the text read from the page" : "from the text read from the pages alone: " + d.picturesWhy());
        if (o.read()) b.append(o.twoModels() ? "; " + o.checkerName() + (o.checkerName().equals(o.checkerModel()) ? "" : " (" + o.checkerModel() + ")") + " reading, a second model" : "; read by the same model, the only one set, in fresh calls: a weaker check than a second model, since a model tends to approve its own work");
        else if (!quick && o.backTranslated() > 0) b.append("; the back-translation by ").append(o.twoModels() ? o.checkerName() : "the same model, the only one set");
        b.append("\n");
        b.append("- Scores: none are made for a comic in this version. LaBSE and MetricX-24 were measured on paragraphs; on a balloon's line of a few words they reward the literal word over the right one, so a score would flag nothing true.\n");
        b.append("- Units: ").append(plural(o.units(), "speech balloon with words, a unit", "speech balloons with words, each a unit")).append("; translated: ").append(o.translated()).append(".").append(o.keptOriginal() > 0 ? " " + (o.keptOriginal() == 1 ? "One balloon keeps its " + source + ": no translation came back for it" + (quick ? "." : ", nor for its revision.") : o.keptOriginal() + " balloons keep their " + source + ": no translation came back for them" + (quick ? "." : ", nor for their revision.")) : "").append("\n");
        if (!d.askedAgain().isEmpty()) b.append("- Pages asked a second time, their first answer having left a balloon's number out (the lines of such an answer can stand under their neighbours' numbers, so none of them is taken): ").append(d.askedAgain().size()).append(" (").append(d.pagesSaid(d.askedAgain())).append(")")
                .append(d.byBalloon().isEmpty() ? "; the second answer held every balloon." : "; the second answer left one out too on " + d.pagesSaid(d.byBalloon()) + ", whose balloons were then translated one at a call, the page's other lines beside each for the thread.").append("\n");
        if (!quick) {
            long numbers = o.flags().stream().filter(f -> f.kind().equals("number") && f.stands()).map(Translation.Flag::unit).distinct().count();
            boolean han = japanese || o.from().equals("zh");   // the languages that write a number with 千 or 万 after its digits
            b.append("- Numbers: the program compared the numbers each balloon writes in digits with its translation's").append(han ? ", a number written with 千 or 万 against the same number written out (8千 and 8,000)," : ",").append(" and a small number against its word (2 and two); balloons that still lack one after the revisions: ").append(numbers)
                    .append(japanese ? ". A number written in kanji alone is not compared.\n" : han ? ". A number written in Chinese numerals alone is not compared.\n" : ". A number written out in words alone is not compared.\n");
        }
        long decidedNames = o.decided() == null ? 0 : o.decided().entries().stream().filter(DocNames.Entry::name).count();
        long decidedAll = o.decided() == null ? 0 : o.decided().entries().size();
        b.append("- Names and terms: ").append(decidedAll == 0 ? "none was decided for the volume" : "decided for the volume: " + decidedAll + " (" + plural((int) decidedNames, "name", "names") + ", " + plural((int) (decidedAll - decidedNames), "term", "terms") + "; listed below)").append("; the pages' own calls named ").append(d.namesWritten()).append(" of the strings the program found. No original is set beside a name in a balloon.").append(o.scriptLeft() > 0 ? " " + source + " letters that stand in the translation: " + o.scriptLeft() + "." : "").append("\n");
        b.append("- Lettering: ").append(d.set()).append(" of ").append(o.units()).append(" balloons set, ").append(d.capitals() ? "in capitals, " : "").append("at one size a page").append(d.set() == 0 ? "" : " (from " + d.smallestPx() + " px to " + d.largestPx() + " px on these pages)")
                .append(d.ownSize() == 0 ? "" : " but for " + plural(d.ownSize(), "balloon", "balloons") + " set smaller than " + (d.ownSize() == 1 ? "its page (its text takes" : "their page (their text takes") + " no more in its balloon, or a smaller size spared a bad line end)")
                .append(!columnsTarget ? "" : d.inColumns() == 0 ? "; all in lines, no balloon of the original being lettered in columns" : "; " + d.inColumns() + " of them in columns, where the original was lettered in columns, the others in lines (each page below says which)")
                .append(d.otherFont() == 0 ? "" : "; " + plural(d.otherFont(), "balloon is", "balloons are") + " set in another font than " + name + "'s own, that one having no letter for a character in it")
                .append(!d.capitals() ? "" : d.hyphenated() ? "; a long word is broken only where " + name + "'s hyphenation patterns allow" : "; the helper has no hyphenation patterns for " + name + ", so no word is broken")
                .append(d.capitals() ? "" : d.insidePhrase() == 0 ? "; no line ends inside a phrase" : "; " + plural(d.insidePhrase(), "line ends", "lines end") + " inside a phrase (the tables below say in which balloons)")
                .append(d.brokenWords() == 0 ? "" : "; " + plural(d.brokenWords(), "word is", "words are") + " broken at a line's end (the tables below say in which " + (d.brokenWords() == 1 ? "balloon)" : "balloons)"))
                .append(d.phrases().isEmpty() ? "" : "; " + d.phrases())
                .append(d.kept() == 0 ? "" : "; the helper was given the " + plural(d.kept(), "name or term", "names and terms") + " the translation writes, to end no line inside one"
                        + (d.keepBroken() == 0 ? "" : ", and in " + plural(d.keepBroken(), "balloon", "balloons") + " it could not (the tables below say which)")).append(".");
        if (d.leftAsArt() > 0) b.append(" Left as art, as a drawn sound word is: ").append(plural(d.leftAsArt(), "balloon whose lettering is drawn into the art", "balloons whose lettering is drawn into the art")).append("; the tables below hold ").append(d.leftAsArt() == 1 ? "its translation." : "their translations.");
        if (d.notSetCount() > 0) { b.append(" Not set: "); int k = 0; for (var e : d.notSet().entrySet()) b.append(k++ == 0 ? "" : "; ").append(e.getValue()).append(" (").append(e.getKey()).append(")"); b.append("."); }
        b.append("\n");
        if (!quick) b.append("- Fit: a line set under ").append(percent(d.fit().pageShare())).append(" of the size the rest of its page is set at, or under ").append(percent(d.fit().glyphShare())).append(" of the size of the ").append(source).append(" it replaces,")
                .append(d.fit().readable() <= 0 ? "" : " or under the smallest size at which " + name + " reads without effort (" + Math.round(d.fit().readable()) + " px on a page 2,000 px high),")
                .append(" or one that did not go into its balloon at all, went back to the translator once to be said in fewer characters.").append(" Went back: ").append(d.fitted().size()).append("; kept: ").append(d.fittedKept()).append("; lines still that small: ").append(d.belowFloor())
                .append(d.heldByPage() == 0 ? "" : (d.heldByPage() == d.belowFloor() ? (d.belowFloor() == 1 ? ", as large as its page's own size lets it be" : ", all of them as large as their page's own size lets them be") : ", " + d.heldByPage() + " of them as large as their page's own size lets them be")
                        + " (a page is set no larger than its original lettering): saying these shorter would not set them larger, so they are not sent back").append(".\n");
        b.append("\n## Checks\n\n");
        if (quick) b.append("None: the quick tier is the pages read, one call a page, and the lines set. The checked tier, the default, adds the program's checks, the revision of what they flag and the fit; reviewed and thorough add a second reading with the page's picture.\n");
        else {
            long program = o.flags().stream().filter(f -> f.by().equals("program")).count(), back = o.flags().stream().filter(f -> f.by().startsWith("back-translation")).count(), reading = o.flags().size() - program - back, serious = o.flags().stream().filter(Translation.Flag::serious).count();
            b.append("- First, the program, with no model (the part a reader can trust most): every balloon's translation present; every number it writes in digits in its translation; every glossary term, and every name and term decided for the volume, rendered as decided. No length band: a line is fitted to its balloon instead. Flags: ").append(program).append(".\n");
            b.append("- Revision: each flagged balloon translated again in a fresh call, the revision kept when its flags clear: ").append(o.changes().stream().filter(ch -> ch.by().equals("program")).count()).append(" revised, ").append(o.changes().stream().filter(ch -> ch.by().equals("program") && ch.kept()).count()).append(" kept.\n");
            if (o.backTranslated() > 0) b.append("- Back-translation: ").append(o.backTranslated()).append(" of the balloons translated back into ").append(source).append(" and compared with the source, by the program (numbers) and by a reading of the pair. Flags: ").append(back).append(".\n");
            if (o.read()) {
                b.append("- The second reading, by ").append(o.checkerName()).append(", of ").append(o.readSaid()).append(" (").append(o.readUnits()).append(" balloons), a page at a call").append(d.readerPictures() ? ", with the page's picture, the balloons read boxed and numbered" : ", from the text alone, since the reader does not see pictures")
                        .append(", with the typology: accuracy (mistranslation, omission, addition, untranslated), terminology, fluency (grammar, wording), style (register").append(o.directed() ? ", and the direction" : "").append("). Flags: ").append(reading).append("; each balloon flagged major or critical translated again against its flags and read again: ").append(o.changes().stream().filter(ch -> !ch.by().equals("program")).count()).append(" revised.");
                int aside = o.readerAside()[0] + o.readerAside()[1] + o.readerAside()[2];
                if (aside > 0) b.append(" Lines of the reader set aside as no error: ").append(aside).append(".");
                b.append("\n");
            }
            b.append("- Standing after everything: ").append(o.marked()).append(o.marked() == 0 ? "; " : ", in " + plural(o.doubts().size(), "balloon", "balloons") + " listed under Still in doubt below; ").append("major or critical in all: ").append(serious).append("; minor, listed and left: ").append(o.minor().size()).append(".\n");
            if (!o.flags().isEmpty()) b.append("\n").append(Translation.counts(o.flags()));
            b.append("\n## Still in doubt\n\n");
            if (o.doubts().isEmpty()) b.append("Nothing: no flag stands after its revision.\n");
            else {
                b.append("The checks still doubt ").append(plural(o.doubts().size(), "balloon", "balloons")).append(" after ").append(o.doubts().size() == 1 ? "its" : "their").append(" revision, here in the order of the volume. The pages hold the translation alone, with no mark.\n\n| number | page | block | begins | why |\n| --- | --- | --- | --- | --- |\n");
                for (Translation.Doubt x : o.doubts()) { ComicTranslation.Block blk = d.units().get(x.unit()); b.append("| ").append(x.number()).append(" | ").append(d.pageLabels().get(blk.page())).append(" | ").append(blk.number()).append(" | ").append(Translation.cell(x.begins())).append(" | ").append(Translation.cell(x.why() + (x.original() ? " (the balloon keeps its " + source + ")" : ""))).append(" |\n"); }
            }
            if (twice.made()) {   // a doubt of another kind: not whether the translation is right, but what the page says
                b.append("\n## Readings in doubt\n\n");
                if (twice.inLetters() == 0) b.append("Nothing: the comic helper and the translator read the letters of every block alike.\n");
                else {
                    b.append("The comic helper and the translator read the letters of ").append(plural(twice.inLetters(), "block", "blocks")).append(" differently. Each is here with both readings and the one the translation was made from. This is a doubt about what the page says, which a look at the page settles; the checks above do not see it.\n\n")
                            .append("| page | block | the comic helper read | the translator read | translated from |\n| --- | --- | --- | --- | --- |\n");
                    for (int p = 0; p < d.rows().size(); p++) for (ComicTranslation.Row r : d.rows().get(p)) {
                        ComicTranslation.Second s = r.block().second();
                        if (s == null || !s.inLetters()) continue;
                        b.append("| ").append(d.pageLabels().get(p)).append(" | ").append(r.block().number()).append(" | ").append(s.helper().isEmpty() ? "(nothing)" : Translation.cell(s.helper()) + (r.block().sure() < 0 ? "" : " (sure " + sureSaid(r.block().sure()) + ")")).append(" | ").append(Translation.cell(Untrusted.line(s.read(), 300)))
                                .append(" | ").append(s.taken() ? "the translator's reading" : "the comic helper's reading: " + s.why()).append(" |\n");
                    }
                }
            }
        }
        if (o.directed()) {
            b.append("\n## Where the direction could not be kept\n\n");
            if (o.notKept() == null || o.notKept().isEmpty()) b.append("The translator named no place where the direction asks for something a line could not carry over (such as a pun or a play on words).\n");
            else { b.append("The translator named these places, and translated each one's sense:\n\n"); for (var e : o.notKept().entrySet()) b.append("- ").append(at(d, e.getKey()).replaceFirst("^p", "P")).append(": ").append(Untrusted.line(e.getValue(), 300)).append("\n"); }
        }
        if (!quick && !d.fitted().isEmpty()) {
            b.append("\n## The fit\n\nEach line that went back, what it was and the size it was set at (0: it did not go into its balloon), the characters it was asked to keep to, what it became and the size that was set at.\n\n| page | block | was | at | at most | became | at | kept |\n| --- | --- | --- | --- | --- | --- | --- | --- |\n");
            for (ComicTranslation.Fitted f : d.fitted()) { ComicTranslation.Block blk = d.units().get(f.unit()); b.append("| ").append(d.pageLabels().get(blk.page())).append(" | ").append(blk.number()).append(" | ").append(Translation.cell(f.before())).append(" | ").append(f.beforePx()).append(" px | ").append(f.budget()).append(" characters | ").append(Translation.cell(f.after())).append(" | ").append(f.kept() ? f.afterPx() + " px" : "").append(" | ").append(f.kept() ? "yes" : "no: " + Translation.cell(f.why())).append(" |\n"); }
        }
        boolean sure = false;   // whether the helper said how sure its reader was of any block: then the tables have the number beside the reading
        for (List<ComicTranslation.Row> rs : d.rows()) for (ComicTranslation.Row r : rs) if (r.block().sure() >= 0) sure = true;
        b.append("\n## Pages\n\nEvery block the helper found, in reading order, with the number its box carries on the picture the translator was shown. \"look\" is the helper's own word that a person should look at a block, and why.")
                .append(sure ? " \"sure\" is the helper's reader's own confidence in what it read in the block itself, from 0 to 1 (the lowest of its lines'), also where the text beside it is the translator's reading" + (d.twice().made() ? ". Where it is at least " + ComicTranslation.SECOND_SURE + " the helper's letters stand against a second reading that differs in one; under that the number is a hint, which decides nothing." : ": a hint, which decides nothing here.") + " Most blocks with one wrong letter score above 0.9." : "")
                .append(twice.made() ? " A block read a second time is given as the translation was made from it: in the translator's reading where that was taken, in the comic helper's where it was not." : "").append("\n");
        for (int p = 0; p < d.rows().size(); p++) {
            b.append("\n### Page ").append(d.pageLabels().get(p)).append(" (").append(d.pageNames().get(p)).append(")\n\n");
            if (d.rows().get(p).isEmpty()) { b.append("No lettering was found on this page.\n"); continue; }
            if (twice.made()) {
                int asked = 0, differ = 0;
                List<String> stands = new ArrayList<>();   // the blocks whose answer was not taken, each with why: where no answer came, nothing else in the note names the block
                for (ComicTranslation.Row r : d.rows().get(p)) { ComicTranslation.Second s = r.block().second(); if (s == null) continue; asked++; if (s.differs()) differ++; if (!s.taken()) stands.add("block " + r.block().number() + " (" + s.why() + ")"); }
                if (asked > 0) b.append("Read a second time: ").append(plural(asked, "block", "blocks")).append("; ").append(differ > 0 ? plural(differ, "reading differs", "readings differ") + " from the comic helper's" : asked == 1 ? "its two readings are the same" : "the two readings are the same in every one").append(".")
                        .append(stands.isEmpty() ? "" : " Not taken, the comic helper's reading standing: " + String.join(", ", stands) + ".").append("\n\n");
            }
            if (columnsTarget) {   // whether the page was set in columns: where the target is written so, and the original lettered the block so
                long set = d.rows().get(p).stream().filter(r -> r.set().matches("\\d+ px.*")).count(), columns = d.rows().get(p).stream().filter(ComicTranslation.Row::columns).count();
                if (set > 0) b.append(columns == 0 ? "Set in lines." : columns == set ? "Set in columns." : "Set in columns: " + columns + " of " + set + " balloons; the others in lines.").append("\n\n");
            }
            b.append("| block | kind | ").append(japanese ? "Japanese, with the readings printed beside it" : source + ", as read").append(sure ? " | sure" : "").append(" | translation | set | look |\n| --- | --- | --- | --- | --- | --- |").append(sure ? " --- |" : "").append("\n");
            for (ComicTranslation.Row r : d.rows().get(p)) {
                ComicTranslation.Block blk = r.block();
                String kind = blk.kind().equals(ComicTranslation.SOUND) ? "sound word" : blk.kind().equals(ComicTranslation.OUTSIDE) ? "outside a balloon" : blk.caption() ? "caption box" : blk.openGround() ? "speech on open ground" : "speech";
                b.append("| ").append(blk.number()).append(" | ").append(kind).append(" | ").append(Translation.cell(blk.shown())).append(!sure ? "" : " | " + sureSaid(blk.sure())).append(" | ").append(Translation.cell(r.translation() == null ? "" : clipped(r.translation()))).append(" | ").append(Translation.cell(r.set())).append(" | ").append(Translation.cell(String.join("; ", blk.look()))).append(" |\n");
            }
        }
        if (!o.flags().isEmpty()) {
            Map<Integer, Integer> numbers = new TreeMap<>(); for (Translation.Doubt x : o.doubts()) numbers.put(x.unit(), x.number());
            b.append("\n## Flags\n\n| page | block | category | kind | severity | by | words | why | after revision |\n| --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
            for (Translation.Flag f : o.flags()) { ComicTranslation.Block blk = d.units().get(f.unit()); b.append("| ").append(d.pageLabels().get(blk.page())).append(" | ").append(blk.number()).append(" | ").append(f.category()).append(" | ").append(f.kind()).append(" | ").append(f.severity()).append(" | ").append(f.by()).append(" | ").append(Translation.cell(f.words())).append(" | ").append(Translation.cell(f.why())).append(" | ").append(f.stands() ? "stands (in doubt, number " + numbers.getOrDefault(f.unit(), 0) + ")" : f.revised() ? "cleared" : Translation.afterSaid(f)).append(" |\n"); }
        }
        if (!o.changes().isEmpty()) {
            b.append("\n## What changed\n\n| page | block | revision | by |\n| --- | --- | --- | --- |\n");
            for (Translation.Change ch : o.changes()) { ComicTranslation.Block blk = d.units().get(ch.unit()); b.append("| ").append(d.pageLabels().get(blk.page())).append(" | ").append(blk.number()).append(" | ").append(ch.kept() ? "kept" : "not kept: its flags did not clear").append(" | ").append(ch.by()).append(" |\n"); }
        }
        b.append("\n## Glossary\n\n");
        if (o.glossaryApplied().isEmpty()) b.append("- No entry of the library's glossary (catalog/glossary.json) applied to this comic.\n");
        else b.append("- From the library's glossary, given with every page that holds the term").append(quick ? "" : " and checked in each balloon").append(": ").append(String.join("; ", o.glossaryApplied().entrySet().stream().map(e -> e.getKey() + " → " + e.getValue()).toList())).append(".\n");
        b.append("\n## Names and terms decided for this volume\n\n");
        boolean hepburn = japanese && ComicTranslation.LATIN.contains(o.to());
        b.append("The program found the strings before the first page, from every page's reading: the strings the speech repeats").append(japanese ? ", and the words with a reading printed beside them. A string was put to a page's call once it had a reading or stood on its second page" : ". A string was put to a page's call once it stood on its second page")
                .append("; the call named the words its own lines wrote for it, and the program took the answer only when those words stand in that page's translation").append(hepburn ? " and, for a string with a printed reading, hold the reading's spelling" : "")
                .append(". The pages after it were given the words to write the same way. What is decided is decided after the first pass, as for any document:\n\n");
        b.append(Translation.namesSection(o, null, c, units -> { List<String> where = new ArrayList<>(); for (int u : units) where.add(at(d, u)); return String.join("; ", where); }));   // a balloon by its page and block: a unit's number says nothing to a reader of a comic
        if (!d.readingSpellings().isEmpty()) {
            b.append("\nReadings printed beside words in this comic").append(ComicTranslation.LATIN.contains(o.to()) ? ", each with the spelling it has as a name: the translator is given it for a word of two characters or more, and a name is held to it, a term is not. A long o and a long u are written short (さとう Sato); a long mark in a kanji's reading after the e row is written ei (テーダイ Teidai). Where the spelling is not what the name should be, the glossary corrects it" : "").append(":\n\n");
            for (var e : d.readingSpellings().entrySet()) b.append("- ").append(e.getKey()).append(" = ").append(e.getValue()).append("\n");
        }
        if (!d.namesRefused().isEmpty()) { b.append("\nAnswers of the pages' calls that were not taken, and why:\n\n"); for (String r : d.namesRefused()) b.append("- ").append(Untrusted.line(r, 300)).append("\n"); }
        b.append("\n## What this version does not do\n\n");
        b.append("- A drawn sound word is left as art. It is found only when the helper's detector boxes it, and lettering drawn into the art is mostly not boxed; the ones found are glossed in the tables above. A balloon whose lettering joins the art is left as art too, and translated in the tables above.\n");
        b.append("- Text outside a balloon (a sign, a caption on the art) is read and translated in the tables above, and never set into the page.\n");
        if (japanese) b.append("- A reading printed outside a balloon, or over a screen tone, is not read.\n");
        if (japanese) b.append("- A short form of a name, such as 帝大出 for a graduate of 帝都大学, can be mistranslated: the translator is told to work it out from the names already written, and nothing checks that it did.\n");
        if (twice.made()) b.append("- A misreading that the comic helper and the translator share stands, and nothing marks it. Where the two differ the translator's reading is the one used, also where the helper's was the right one: Readings in doubt lists those blocks. Text outside a balloon and drawn sound words are read by the helper alone.\n");
        else b.append(japanese ? "- Japanese lettering is read once, by the comic helper: a model that sees reads it worse than the helper's reader does, so it is not read a second time. A misreading stands, marked only where the helper says \"look\". A reading spread along a long word can come out cut short.\n"
                : "- The lettering was read once in this run: a misreading stands, marked only where the helper says \"look\". At the checked tier and above a translator that sees pictures reads each balloon a second time.\n");
        b.append("- Two balloons that touch are cut apart at the detector's box, in a straight line.\n");
        b.append("- The helper's thresholds (what is a sound word, what is a reading, what is very small text) were set on 29 pages of one manga, and the sizes at which a line goes back to be said shorter were chosen on the same volume, for Japanese into Latin capitals; neither has been measured on many books")
                .append(japanese && d.capitals() ? "" : d.capitals() ? ", and for " + source + " into " + name + " not at all" : "; for " + name + " the smallest size that reads was chosen on one font's ladder of sizes and one set of proof pages").append(".\n");
        if (o.to().equals("zh")) b.append("- Chinese is written in simplified characters: nothing in this version asks for the traditional ones.\n");
        b.append("- A comic lettered from right to left (Arabic, Hebrew), in Thai or in a script of India is not read, and no comic is lettered in those.\n");
        b.append("\n## On the shelves\n\n");
        b.append("- The translation: ").append(o.shelved() == null ? "not shelved (no library)" : o.shelved().getFileName() + ", the volume's script page by page and block by block, with the archive as its file").append(o.collection().isEmpty() ? "" : " (collection " + o.collection() + ")").append("\n");
        b.append("- The original: ").append(o.originalShelved() == null ? "not shelved" : o.originalShelved().getFileName() + ", its script as read").append("\n");
        return b.toString();
    }
}
