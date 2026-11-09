package org.researchzosho.librarian;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A picture under a chat reply (0.7.0): when the person's words ask about a thing a picture can show — a mechanism, a place, an object, a
 * person by name, a process with a known diagram, read by the same rules as a report's sub-questions ({@link RunPictures#sectionAsk}) — the
 * library, once the model's reply is written, asks for one picture of that thing (the shelves first, then the web, judged under the
 * shows-rule in the words' connection) and places it under the reply with its line and a sentence that says why it is there. At most one
 * a reply; none when the reply carries a picture already, when the words asked for pictures ({@link ImagesTurn} answers those), when the
 * turn started or offered a research run, or when no model sees. The reply's own text is untouched. One search and at most six judge calls.
 */
final class ChatPicture {
    private ChatPicture() { }

    /** The line that shows the picture and the sentence under it; "" when none is placed. */
    static String under(LibraryStore store, String words, String reply, List<String> toolsCalled, boolean noOffer) {
        if (store == null || words == null || words.isBlank() || reply == null || reply.contains("![") || !RunPictures.on() || !noOffer) return "";
        if (ImagesTurn.asksForPictures(words)) return "";
        if (toolsCalled != null) for (String t : toolsCalled) if (t != null && t.toLowerCase(Locale.ROOT).contains("research")) return "";
        ModelEntries.Entry eyes;
        try { eyes = ModelEntries.seeing(); } catch (RuntimeException e) { return ""; }
        if (eyes == null) return "";
        String said = TranslateTurn.withoutAttachment(words).strip();
        RunPictures.SectionAsk ask = RunPictures.sectionAsk(said, Set.of());
        if (ask.skipped() != null) return "";
        RunPictures pictures = new RunPictures(store, l -> { }, (k, o) -> { });
        RunPictures.Asked got = pictures.askFor("the chat", Acquisitions.compress(said, 80), ask.what(), ask.kind(), "");
        if (got.picture() == null) return "";
        Images.Judged j = got.picture().judged();
        return got.picture().line() + "\n\n" + Chrome.now().t("Picture: {0}, judged \"{1}\": yes — {2} (kept as {3})", got.picture().title(), j.criteria(), j.why(), got.picture().kept().id());
    }
}
