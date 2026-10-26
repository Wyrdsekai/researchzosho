package org.researchzosho.librarian;

import java.io.IOException;
import java.util.List;

/** The interview (0.6.6): the library asks what it does not know about a subject — its open questions — and what the person answers is filed as told, in their words, by the chat's library_tell. */
public final class Interview {
    private Interview() { }

    /** What the chat says on "/interview <subject>": the open questions on the subject, numbered, and the invitation to answer any in the person's own words. */
    public static String questions(LibraryStore store, String subject) throws IOException {
        String s = subject == null ? "" : subject.strip();
        if (s.isEmpty()) return "Whom shall I interview you about? /interview <a person or an organisation>";
        Dossier.Gathered d = Dossier.gather(store, s);
        List<Frontier.Line> open = d.questions();
        StringBuilder b = new StringBuilder();
        if (open.isEmpty()) b.append("The library holds ").append(d.claims().size()).append(d.claims().size() == 1 ? " claim" : " claims").append(" on ").append(d.label()).append(" and has no open question on them yet. ");
        else {
            b.append("What the library does not know about ").append(d.label()).append(", ").append(open.size()).append(open.size() == 1 ? " question" : " questions").append(":\n");
            int n = 0; for (Frontier.Line l : open) { b.append(++n).append(". ").append(l.text()).append('\n'); if (n == 8) { if (open.size() > 8) b.append("… and ").append(open.size() - 8).append(" more on the dossier.\n"); break; } }
        }
        b.append("Tell me what you know of any of these, or anything else about ").append(d.label()).append(", in your own words: I file it as told, dated, with you as the source, and the names, dates and events it raises become questions for the next run.");
        return b.toString();
    }
}
