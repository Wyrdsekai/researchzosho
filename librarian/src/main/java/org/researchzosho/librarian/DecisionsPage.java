package org.researchzosho.librarian;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The page "Decisions": what only the family can settle, in one place. First the names that may be one person, then the claims
 * that disagree, then how many questions about names and families wait on the Who is who page, then the people whose web pages wait
 * for the family's word. Every answer says what it will do before it is given,
 * and "I cannot tell" asks the research for a record instead of guessing.
 */
final class DecisionsPage {
    private DecisionsPage() { }

    static String body(LibraryStore store, Patrons.Patron patron, String done) throws IOException {
        Pages.writersOnly(store, patron);   // the page acts on the library: answers change claims and the research
        List<FamilyDecisions.Pair> pairs = FamilyDecisions.pairs(store);
        List<FamilyDecisions.Clash> clashes = FamilyDecisions.clashes(store);
        List<FamilyIdentity.Question> who = FamilyIdentity.open(store);
        List<FamilyNameQuestions.Question> names = FamilyNameQuestions.open(store);
        Graph g = FamilyPeople.view(store);
        StringBuilder b = new StringBuilder("<p class=\"k\">These are the things only your family can decide. The library worked out what agrees and what differs; "
                + "the decision is yours. Each answer says what it will do before you give it, and how to change it afterwards. If you cannot tell, say so: "
                + "the library then writes down a question for a record that settles it. The next genealogy research looks for that record, unless you give it --skip-living "
                + "and somebody the question names may be living.</p>");
        if (!done.isBlank()) b.append("<p><strong>").append(Pages.esc(done)).append("</strong></p>");
        if (pairs.isEmpty() && clashes.isEmpty() && who.isEmpty() && names.isEmpty()) return b.append("<p><strong>Nothing is waiting for your decision.</strong></p>").toString();
        if (!pairs.isEmpty()) {
            b.append("<h2>One person or two?</h2>");
            for (FamilyDecisions.Pair p : pairs) b.append(pair(p, FamilyDecisions.mayGoOut(g, List.of(p.fold(), p.into()), true), FamilyDecisions.namesTheLiving(g, List.of(p.fold(), p.into()))));
        }
        if (!clashes.isEmpty()) {
            b.append("<h2>Two sources that disagree</h2>");
            for (FamilyDecisions.Clash c : clashes) b.append(clash(c, FamilyDecisions.mayGoOut(g, List.of(c.person()), true), FamilyDecisions.namesTheLiving(g, List.of(c.person()))));
        }
        if (!names.isEmpty()) {
            b.append("<h2>Names and families</h2><p><a href=\"/who\">").append(names.size()).append(names.size() == 1 ? " question about names and families waits" : " questions about names and families wait")
             .append("</a> for your answer: ").append(Pages.esc(FamilyNameQuestions.counted(names))).append(". They are on the Who is who page, one at a time, and each answer says what it will do before you give it.</p>");
        }
        int room = Math.max(0, FamilyDecisions.CAP - pairs.size() - clashes.size());
        if (!who.isEmpty()) {
            b.append("<h2>Who is who on the web</h2><ul>");
            for (FamilyIdentity.Question q : who.stream().limit(room).toList())
                b.append("<li><a href=\"/who?person=").append(URLEncoder.encode(q.person(), StandardCharsets.UTF_8)).append("\">").append(Pages.esc(q.person())).append("</a> <span class=\"k\">")
                 .append(q.candidates().size()).append(" possible matches. Your answer tells the research which pages are about your relative.</span></li>");
            if (who.size() > room) b.append("<li class=\"k\">").append(who.size() - room).append(room == 0 ? " people wait" : " more wait").append(" on the <a href=\"/who\">Who is who</a> page.</li>");
            b.append("</ul>");
        }
        return b.toString();
    }

    /** What "I cannot tell" does with the question: {@code goesOut} when the research may ask it; {@code living} when somebody it names may be living. */
    private static String unsure(boolean goesOut, boolean living, String forWhom) {
        return goesOut ? " to the research for " + forWhom + ": the next researchzosho genealogy research asks it, also when they were searched for before"
                        + (living ? ", unless you give that command --skip-living, since somebody it names may be living" : "") + ". It waits in the open questions until you answer here."
                : " to your open questions. The research asks it once everybody it names is in your library; until then it stays with you.";
    }

    private static String pair(FamilyDecisions.Pair p, boolean goesOut, boolean living) {
        StringBuilder b = new StringBuilder("<form method=\"post\" action=\"/decide\" style=\"border:1px solid #8884;border-radius:6px;padding:.6em .9em;margin:.7em 0\">")
                .append("<input type=\"hidden\" name=\"kind\" value=\"pair\"><input type=\"hidden\" name=\"code\" value=\"").append(p.code()).append("\">")
                .append("<p>").append(Pages.esc(p.said().replaceFirst(" If they are one person: researchzosho .*$", ""))).append("</p><ul>");
        String moving = p.moving().isEmpty() ? "No claim is written about “" + p.fold() + "” yet." : "The " + (p.moving().size() == 1 ? "claim" : p.moving().size() + " claims") + " about “" + p.fold() + "” (" + String.join(", ", p.moving().stream().limit(8).toList()) + (p.moving().size() > 8 ? ", …" : "") + ") are then about “" + p.into() + "”.";
        b.append("<li><button name=\"do\" value=\"one\">One person</button> joins “").append(Pages.esc(p.fold())).append("” into “").append(Pages.esc(p.into())).append("”. ").append(Pages.esc(moving))
         .append(p.reason().isBlank() ? "" : " The reason kept with it: " + Pages.esc(p.reason()) + ".").append(" <code>researchzosho graph unmerge \"").append(Pages.esc(p.fold())).append("\"</code> takes it back.</li>");
        b.append("<li><button name=\"do\" value=\"two\">Two people</button> writes down that they are two people, and the library stops asking about this pair. <code>researchzosho genealogy different \"")
         .append(Pages.esc(p.fold())).append("\" \"").append(Pages.esc(p.into())).append("\" --undo</code> takes it back.</li>");
        b.append("<li><button name=\"do\" value=\"unsure\">I cannot tell</button> adds a question for a record that tells them apart").append(unsure(goesOut, living, "both")).append("</li>");
        return b.append("</ul></form>").toString();
    }

    private static String clash(FamilyDecisions.Clash c, boolean goesOut, boolean living) {
        StringBuilder b = new StringBuilder("<form method=\"post\" action=\"/decide\" style=\"border:1px solid #8884;border-radius:6px;padding:.6em .9em;margin:.7em 0\">")
                .append("<input type=\"hidden\" name=\"kind\" value=\"clash\"><input type=\"hidden\" name=\"one\" value=\"").append(Pages.esc(c.one().id())).append("\"><input type=\"hidden\" name=\"other\" value=\"").append(Pages.esc(c.other().id())).append("\">")
                .append("<p>Two sources give ").append(Pages.esc(c.person())).append(" two different values for the ").append(Pages.esc(c.what())).append(".</p><ul>");
        for (Finding[] f : new Finding[][]{{c.one(), c.other()}, {c.other(), c.one()}}) {
            b.append("<li><button name=\"do\" value=\"").append(f[0] == c.one() ? "keep1" : "keep2").append("\">Keep ").append(Pages.esc(FamilyDecisions.kept(c, f[0]))).append("</button> accepts <a href=\"/entry/").append(Pages.esc(f[0].id())).append("\">").append(Pages.esc(f[0].id())).append("</a> (")
             .append(Pages.esc(f[0].title().replaceFirst("[.。]$", ""))).append(", from ").append(Pages.esc(FamilyChecks.fromAll(f[0]))).append(") and marks <a href=\"/entry/").append(Pages.esc(f[1].id())).append("\">").append(Pages.esc(f[1].id())).append("</a> (")
             .append(Pages.esc(f[1].title().replaceFirst("[.。]$", ""))).append(", from ").append(Pages.esc(FamilyChecks.fromAll(f[1]))).append(") as disputed, with your choice as the reason. Both stay on record. ")
             .append("To choose the other one after all: ").append(Pages.esc(changeMind(f[0], f[1]))).append("</li>");
        }
        b.append("<li><button name=\"do\" value=\"unsure\">I cannot tell</button> adds a question for the record closest to the event").append(unsure(goesOut, living, Pages.esc(c.person()))).append("</li>");
        return b.append("</ul></form>").toString();
    }

    /** An answer from the page. Returns where to go next, with what was done. */
    static String post(LibraryStore store, Patrons.Patron patron, Map<String, String> form) throws IOException {
        Pages.writersOnly(store, patron);
        String by = patron == null || patron.name() == null || patron.name().isBlank() ? "person" : patron.name();
        String what = form.getOrDefault("do", "");
        String done;
        if (form.getOrDefault("kind", "").equals("pair")) {
            FamilyDecisions.Pair p = FamilyDecisions.pairs(store).stream().filter(x -> x.code().equals(form.getOrDefault("code", ""))).findFirst().orElse(null);
            if (p == null) return back("That pair is not waiting any more: it was answered, or its claims changed. The page shows what waits now.");
            switch (what) {
                case "one" -> {
                    Graph.Merged m = FamilyDecisions.one(store, p, by);
                    done = "“" + p.fold() + "” is joined into “" + p.into() + "”. " + (m.claims().isEmpty() ? "No claim was written about “" + p.fold() + "”." : m.claims().size() + (m.claims().size() == 1 ? " claim" : " claims") + " about “" + p.fold() + "” " + (m.claims().size() == 1 ? "is" : "are") + " now about “" + p.into() + "”.");
                }
                case "two" -> { FamilyDecisions.two(store, p, by); done = "“" + p.fold() + "” and “" + p.into() + "” are written down as two people."; }
                case "unsure" -> {
                    FamilyDecisions.cannotTell(store, FamilyDecisions.recordQuestion(p), by);
                    Graph g = FamilyPeople.view(store);
                    List<String> both = List.of(p.fold(), p.into());
                    done = FamilyDecisions.mayGoOut(g, both, true) ? "The next time you give the command researchzosho genealogy research, the search for “" + p.fold() + "” and “" + p.into() + "” asks for a record that tells them apart, also when they were searched for before. To start it now: researchzosho genealogy research \"" + p.into() + "\""
                              + (FamilyDecisions.namesTheLiving(g, both) ? ". One of them may be living, so a research given --skip-living leaves this question out." : "")
                            : "A question for a record that tells “" + p.fold() + "” and “" + p.into() + "” apart is in your open questions. The research asks it once both are in your library.";
                }
                default -> throw ProtocolError.invalidArgs("That answer is not known. Go back and try again.");
            }
        } else if (form.getOrDefault("kind", "").equals("clash")) {
            FamilyDecisions.Clash c = FamilyDecisions.clashes(store).stream().filter(x -> x.one().id().equals(form.getOrDefault("one", "")) && x.other().id().equals(form.getOrDefault("other", ""))).findFirst().orElse(null);
            if (c == null) return back("Those two claims are not waiting any more: one was decided, or they changed. The page shows what waits now.");
            switch (what) {
                case "keep1", "keep2" -> {
                    Finding kept = what.equals("keep1") ? c.one() : c.other(), other = what.equals("keep1") ? c.other() : c.one();
                    FamilyDecisions.keep(store, c, kept, by);
                    done = kept.id() + " is accepted, and " + other.id() + " is marked as disputed. To choose the other one after all: " + changeMind(kept, other);
                }
                case "unsure" -> {
                    FamilyDecisions.cannotTell(store, FamilyDecisions.recordQuestion(c), by);
                    Graph g = FamilyPeople.view(store);
                    done = FamilyDecisions.mayGoOut(g, List.of(c.person()), true) ? "The next time you give the command researchzosho genealogy research, the search for “" + c.person() + "” asks for the record closest to the event, also when they were searched for before. To start it now: researchzosho genealogy research \"" + c.person() + "\""
                              + (FamilyDecisions.namesTheLiving(g, List.of(c.person())) ? ". “" + c.person() + "” may be living, so a research given --skip-living leaves this question out." : "")
                            : "A question for the record closest to the event is in your open questions. The research asks it once “" + c.person() + "” is in your library.";
                }
                default -> throw ProtocolError.invalidArgs("That answer is not known. Go back and try again.");
            }
        } else throw ProtocolError.invalidArgs("That answer is not known. Go back and try again.");
        return back(done);
    }

    /** The two commands that turn a "Keep" around: the disputed claim accepted, the kept one disputed. Both stay on record either way. */
    static String changeMind(Finding kept, Finding other) {
        return "researchzosho accept " + other.id() + " and then researchzosho dispute " + kept.id() + " \"the reason\"";
    }

    private static String back(String said) { return "/decide?done=" + URLEncoder.encode(said, StandardCharsets.UTF_8); }
}
