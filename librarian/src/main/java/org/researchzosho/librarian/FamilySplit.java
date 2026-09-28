package org.researchzosho.librarian;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Two people of one name, held apart. The map makes one node of one name, so a father and a son who share it, or two
 * unrelated men of one village, arrive as one person with three parents and two birth years. Splitting moves the claims
 * that belong to the other person onto a name that tells them apart — "John Ellis (born 1851)" — and says so on each
 * claim. It is the person's act, like a merge: the checks only point at where it is needed.
 */
public final class FamilySplit {

    private FamilySplit() { }

    public record Outcome(int moved, List<String> notFound, List<String> notAbout) { }

    /** The note a split leaves on each claim it moves. */
    private static final Pattern NOTE = Pattern.compile("^this claim is about (.+), not the other (.+): two people of one name, held apart$");

    /**
     * The note an answer leaves on each claim it moves, when the family said who a name written alone is in one source's words ({@link
     * FamilyNameQuestions}): the question, the person the claim is about now, and the name the words write.
     */
    private static final Pattern ANSWER = Pattern.compile("^the family's answer to the question ([0-9a-f]{6}): this claim is about (.+), whom these words write as (.+)$");

    /**
     * The name the latest split or answer moved this claim to and the name it was moved from, or null when none moved it. A claim moved so
     * is the owner's decision: a reset keeps it, and the same words read again stay with the person it was moved to.
     */
    static String[] moved(Finding f) {
        String[] out = null;
        for (Finding.Note n : f.notes()) {
            Matcher m = NOTE.matcher(n.text());
            if (m.matches()) { out = new String[]{m.group(1), m.group(2)}; continue; }
            m = ANSWER.matcher(n.text());
            if (m.matches()) out = new String[]{m.group(2), m.group(3)};
        }
        return out;
    }

    /** The question whose answer moved this claim, the name it moved it to and the name the words write, or null when no answer moved it. */
    static String[] movedByAnswer(Finding f) {
        String[] out = null;
        if (f == null) return null;
        for (Finding.Note n : f.notes()) { Matcher m = ANSWER.matcher(n.text()); if (m.matches()) out = new String[]{m.group(1), m.group(2), m.group(3)}; }
        return out;
    }

    /**
     * The claims one source's words give about a name written alone, moved to the person the family said it is: each claim's triple names
     * {@code to} where it named the entry {@code fromId}, and a note says which answer moved it and how the words write the name. The words
     * of the claim stay the source's. The entry keeps every other claim, so the same name in other words stays as it was. Returns the
     * claims moved.
     */
    static List<String> move(LibraryStore store, Graph g, String fromId, List<String> claimIds, String to, String code) throws IOException {
        List<String> out = new ArrayList<>();
        for (String id : claimIds) {
            Finding f = store.finding(id);
            if (f == null || f.triple() == null) continue;
            Finding.Triple t = f.triple();
            boolean subject = g.nodeOf(f, true).equals(fromId), object = g.nodeOf(f, false).equals(fromId);
            if (!subject && !object) continue;
            String written = subject ? t.subject() : t.object();
            List<Finding.Note> notes = new ArrayList<>(f.notes());
            notes.add(new Finding.Note("person", "person", LocalDate.now().toString(), "the family's answer to the question " + code + ": this claim is about " + to + ", whom these words write as " + written));
            store.write(new Finding(f.id(), f.title(), f.subjects(), f.state(), f.claimType(), f.confidence(), f.writer(), f.recordedAt(), f.validAsOf(),
                    f.volatility(), f.reviewBy(), f.sources(), f.supersedes(), f.review(), f.body(), new Finding.Triple(subject ? to : t.subject(), t.predicate(), object ? to : t.object()), notes));
            Changes.append(store, "finding", f.id(), "edited", "the family's answer to the question " + code + " says " + written + " in these words is " + to);
            out.add(f.id());
        }
        if (!out.isEmpty()) store.circulate("family-answer-moved", out.size() + " claim(s) moved to " + to + " by the answer to " + code);
        return out;
    }

    /**
     * The claims an answer moved ({@link #move}), about the name their words write again, with the answer's note taken off. Returns those moved
     * back, each as {claim, the name its words write}.
     */
    static List<String[]> moveBack(LibraryStore store, String code, List<String> claimIds) throws IOException {
        List<String[]> out = new ArrayList<>();
        for (String id : claimIds) {
            Finding f = store.finding(id);
            String[] m = movedByAnswer(f);
            if (m == null || !m[0].equals(code) || f.triple() == null) continue;
            Finding.Triple t = f.triple();
            List<Finding.Note> notes = new ArrayList<>();
            for (Finding.Note n : f.notes()) { Matcher a = ANSWER.matcher(n.text()); if (!(a.matches() && a.group(1).equals(code))) notes.add(n); }
            store.write(new Finding(f.id(), f.title(), f.subjects(), f.state(), f.claimType(), f.confidence(), f.writer(), f.recordedAt(), f.validAsOf(),
                    f.volatility(), f.reviewBy(), f.sources(), f.supersedes(), f.review(), f.body(),
                    new Finding.Triple(t.subject().equals(m[1]) ? m[2] : t.subject(), t.predicate(), t.object().equals(m[1]) ? m[2] : t.object()), notes));
            Changes.append(store, "finding", f.id(), "edited", "the answer to the question " + code + " taken back: the claim is about " + m[2] + " again");
            out.add(new String[]{f.id(), m[2]});
        }
        return out;
    }

    public static Outcome split(LibraryStore store, String name, String newName, List<String> claimIds) throws IOException {
        if (newName == null || newName.isBlank() || Vocabulary.norm(newName).equals(Vocabulary.norm(name))) throw new IllegalArgumentException("the new name must differ from \"" + name + "\": add what tells them apart, like \"" + name + " (born 1851)\"");
        Graph g = FamilyPeople.view(store);
        String id = g.nodeIdOf(name);
        if (g.nodeIdOf(newName).equals(id)) throw new IllegalArgumentException("\"" + newName + "\" is already another name of \"" + name + "\"");
        Graph.Node old = g.node(id);
        List<String> notFound = new ArrayList<>(), notAbout = new ArrayList<>();
        int moved = 0;
        List<Finding> all = store.scanFindings().findings();
        for (String want : claimIds) {
            Finding f = all.stream().filter(x -> x.id().equals(want) || x.id().startsWith(want + "-")).findFirst().orElse(null);
            if (f == null) { notFound.add(want); continue; }
            Finding.Triple t = f.triple();
            // in genealogy's view a claim's side is the person it is linked to, whatever name it writes
            boolean subject = t != null && g.nodeOf(f, true).equals(id), object = t != null && g.nodeOf(f, false).equals(id);
            if (!subject && !object) { notAbout.add(f.id()); continue; }
            Finding.Triple now = new Finding.Triple(subject ? newName : t.subject(), t.predicate(), object ? newName : t.object());
            String written = subject ? t.subject() : t.object();
            List<Finding.Note> notes = new ArrayList<>(f.notes());
            notes.add(new Finding.Note("person", "person", LocalDate.now().toString(), "this claim is about " + newName + ", not the other " + written + ": two people of one name, held apart"));
            store.write(new Finding(f.id(), f.title().replace(written, newName), f.subjects(), f.state(), f.claimType(), f.confidence(), f.writer(), f.recordedAt(), f.validAsOf(),
                    f.volatility(), f.reviewBy(), f.sources(), f.supersedes(), f.review(), f.body().replace(written, newName), now, notes));
            moved++;
        }
        if (moved > 0) {
            // the second person is the kind the first is; whether they may be living, the graph works out from the claims just moved
            Graph.setKind(store, newName, old == null ? "person" : old.kind());
            // held apart by the person: the checks and the tidying do not put the two forward as one again
            try { Graph.different(store, name, newName, "genealogy split", "two people of one name, held apart"); } catch (IllegalArgumentException movedAll) { }
            store.circulate("family-split", moved + " claim(s) moved from " + name + " to " + newName);
        }
        return new Outcome(moved, notFound, notAbout);
    }
}
