package org.researchzosho.librarian;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Genealogy's own pages, drawn in the library's frame: the family tree, "Who is who" and the decisions, with the home page's family
 * section and the inbox's note. The core reaches them only through {@link Profile#page}, {@link Profile#homeSection} and {@link
 * Profile#inboxNote}.
 */
public final class FamilyPages {

    private FamilyPages() { }

    public static final List<String> PATHS = List.of("/tree", "/who", "/decide", "/person", "/family", "/summary");

    public static List<Profile.Link> menu() {
        return List.of(new Profile.Link("/tree", "Family tree", false), new Profile.Link("/who", "Who is who", true), new Profile.Link("/decide", "Decisions", true), new Profile.Link("/family", "Families", false), new Profile.Link("/summary", "Family summary", false));
    }

    public static Profile.Page page(LibraryStore store, Patrons.Patron patron, String path, String method, Map<String, String> q, Map<String, String> form) throws IOException {
        boolean post = "POST".equals(method);
        return switch (path) {
            case "/tree" -> { Patrons.check(store, patron, Patrons.Level.read); yield new Profile.Page("Family tree", treeBody(store, q.getOrDefault("focus", "")), true, null); }
            case "/who" -> post ? new Profile.Page(null, null, false, whoPost(store, patron, form))
                    : new Profile.Page("Who is who", WhoPage.body(store, patron, q.getOrDefault("person", ""), q.getOrDefault("show", "")), false, null);
            case "/decide" -> post ? new Profile.Page(null, null, false, DecisionsPage.post(store, patron, form))
                    : new Profile.Page("Decisions", DecisionsPage.body(store, patron, q.getOrDefault("done", "")), false, null);
            // a person's names and families, and a family's page: for everybody who may read the library, with nothing to send
            case "/person" -> {
                Patrons.check(store, patron, Patrons.Level.read);
                if (post) throw ProtocolError.invalidArgs("This page shows a person's names and families. It has nothing to send.");
                yield new Profile.Page("Names and families", FamilyNamePages.personPage(store, patron, q.getOrDefault("name", "")), false, null);
            }
            case "/family" -> {
                Patrons.check(store, patron, Patrons.Level.read);
                if (post) throw ProtocolError.invalidArgs("This page shows a family. It has nothing to send.");
                yield new Profile.Page("Families", FamilyNamePages.familyPage(store, patron, q.getOrDefault("name", ""), q.getOrDefault("surname", "")), false, null);
            }
            // what the sources settle about the family, for everybody who may read the library, with nothing to send
            case "/summary" -> {
                Patrons.check(store, patron, Patrons.Level.read);
                if (post) throw ProtocolError.invalidArgs("This page shows a summary of your family. It has nothing to send.");
                yield new Profile.Page("Family summary", FamilySummary.html(FamilySummary.of(store, "1".equals(q.getOrDefault("all", "")))), false, null);
            }
            default -> null;
        };
    }

    /**
     * /tree?focus=…: the family around a person, drawn from the claims. With no name, the people the kinship claims are about, each under
     * their heading ("森田健二 (born 遠藤)") and linked by their label. The page is the same for everyone who may read the library; the
     * handler checks that.
     */
    public static String treeBody(LibraryStore store, String focus) throws IOException {
        StringBuilder b = new StringBuilder("<form method=\"get\" action=\"/tree\" style=\"display:flex;gap:.5em;margin:.4em 0 .8em\"><input name=\"focus\" value=\"" + FamilyTree.esc(focus) + "\" placeholder=\"a person in your family\" style=\"flex:1;font:inherit;padding:.4em .6em\"> <button>Draw</button></form>");
        b.append("<p class=\"k\"><a href=\"/summary\">The summary of your family</a> says what the sources settle about each of your close family, with where each thing comes from.</p>");
        FamilyTree.Tree t = focus.isBlank() ? new FamilyTree.Tree(null, List.of(), List.of()) : FamilyTree.around(store, focus, 5, 4);
        if (t.focus() == null) {
            b.append(focus.isBlank() ? "<p>Type a name. These people have family claims:</p>" : "<p>Nobody of that name has family claims here. These people do:</p>").append("<p>");
            Graph g = FamilyPeople.view(store);
            Set<String> kin = Set.of("parent-of", "child-of", "married-to", "adopted-by", "step-parent-of", "foster-child-of", "sibling-of", "relative-of");
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (Graph.Edge e : g.edges()) if (kin.contains(e.predicate())) for (String id : new String[]{e.from(), e.to()}) if (g.node(id) != null) ids.add(id);
            if (ids.isEmpty()) b.append("none yet. <code>researchzosho genealogy read</code> or <code>genealogy tell</code> starts a family.");
            FamilyNameHistory.Index names = FamilyNameHistory.of(g);
            // the heading is what a person reads; the label is what the link takes
            ids.stream().map(id -> new String[]{names.heading(id), g.node(id).label()}).sorted(Comparator.comparing((String[] n) -> n[0])).forEach(n ->
                    b.append("<a href=\"/tree?focus=").append(URLEncoder.encode(n[1], StandardCharsets.UTF_8)).append("\">").append(FamilyTree.esc(n[0])).append("</a> · "));
            b.append("</p>");
            return b.toString();
        }
        b.append("<p class=\"k\">Every line is a claim: <span style=\"color:#2f6f4f\">green</span> is checked and accepted, <span style=\"color:#9a948a\">grey</span> is still only somebody's account, <span style=\"color:#c3402f\">red dots</span> is disputed. A thin dotted line rests on a clue only: a family account or somebody else's tree, not yet seen in a record. A dashed line is an adoption, a step-parent or a foster parent, a double line a marriage. Click a line for its claim, a person to draw the tree around them.</p>");
        b.append("<div style=\"overflow:auto;border:1px solid var(--line);background:var(--card)\">")
         .append(FamilyTree.svg(t, n -> "/tree?focus=" + URLEncoder.encode(n, StandardCharsets.UTF_8), f -> "/entry/" + f)).append("</div>");
        // drill in: from the drawing to the person's names and the families they belonged to
        String focusLabel = URLEncoder.encode(t.focus().label(), StandardCharsets.UTF_8);
        b.append("<p><a href=\"/person?name=").append(focusLabel).append("\">Names and families of ").append(FamilyTree.esc(t.focus().heading())).append("</a>")
         .append(" · <a href=\"/map?focus=").append(focusLabel).append("\">Everything the library connects to ").append(FamilyTree.esc(t.focus().heading())).append("</a></p>");
        List<FamilyChecks.Problem> problems = FamilyChecks.open(store, FamilyChecks.check(store));   // what somebody said is right as it stands is left off
        if (!problems.isEmpty()) {
            b.append("<h2>To look at</h2>");
            if (problems.stream().anyMatch(pr -> pr.kind().equals("same-person?"))) b.append("<p class=\"k\">Names that may be one person can be answered on the <a href=\"/decide\">Decisions</a> page, where each answer says what it will do.</p>");
            b.append("<ul>");
            for (FamilyChecks.Problem p : problems) {
                b.append("<li><b>").append(FamilyTree.esc(p.kind())).append("</b> ").append(FamilyTree.esc(p.text()));
                for (String f : p.findings().stream().filter(x -> !x.isBlank()).distinct().toList()) b.append(" <a href=\"/entry/").append(FamilyTree.esc(f)).append("\">").append(FamilyTree.esc(f)).append("</a>");
                b.append("</li>");
            }
            b.append("</ul>");
        }
        return b.toString();
    }

    /**
     * An answer from the page "Who is who": which of the people shown is the relative, none of them, later, or something told; or, for a
     * question about names and families ({@code kind=names}), the family's answer to it. Goes on to the next question that waits.
     */
    static String whoPost(LibraryStore store, Patrons.Patron patron, Map<String, String> form) throws IOException {
        Pages.writersOnly(store, patron);
        if ("names".equals(form.getOrDefault("kind", ""))) return FamilyNameQuestions.post(store, patron, form);
        String person = form.getOrDefault("person", "").strip();
        String told = form.getOrDefault("told", "").strip();
        if (!told.isEmpty()) FamilyIdentity.told(store, person, told);
        List<String> picked = new ArrayList<>();
        for (var e : form.entrySet()) if (e.getKey().matches("c\\d+") && !e.getValue().isBlank()) picked.add(e.getKey().substring(1));
        String what = form.getOrDefault("do", "");
        if (what.equals("confirmed") && picked.isEmpty()) return "/who?person=" + URLEncoder.encode(person, StandardCharsets.UTF_8) + "&show=pick";
        if (List.of("confirmed", "none", "unsure").contains(what)) FamilyIdentity.answer(store, person, picked, what);
        else if (!what.equals("told")) throw ProtocolError.invalidArgs("That answer is not known. Go back and try again.");
        return what.equals("told") ? "/who?person=" + URLEncoder.encode(person, StandardCharsets.UTF_8) : "/who";
    }

    /**
     * The home page's family section: the tree, and how many "who is who" questions and questions about names and families wait for
     * somebody who may answer them.
     */
    public static String homeSection(LibraryStore store, boolean writer) throws IOException {
        int waits = writer ? FamilyIdentity.open(store).size() : 0;
        List<FamilyNameQuestions.Question> asked = writer ? FamilyNameQuestions.open(store) : List.of();
        int names = asked.size();
        return "<h2>Your family</h2><p><a href=\"/tree\">The family tree</a> draws the generations around any person: type a name, and click a name in the tree to move to them. "
                + "<a href=\"/summary\">The summary</a> says what the sources settle about your close family, and where they disagree with your notes. "
                + (!writer ? "" : waits > 0 ? "<a href=\"/who\">Who is who</a>: <strong>" + waits + (waits == 1 ? " question is" : " questions are") + "</strong> waiting for you. The library found web pages under your relatives' names and needs you to say which ones are really about them." : "<a href=\"/who\">Who is who</a> is where you tell the library which web pages are really about your relatives.")
                + (names > 0 ? " <a href=\"/who\"><strong>" + names + (names == 1 ? " question about names and families waits" : " questions about names and families wait") + "</strong></a> for you too: " + Pages.esc(FamilyNameQuestions.counted(asked)) + "." : "")
                + "</p>";
    }

    /** The inbox's note: the "who is who" questions and the questions about names and families that wait, for those who may answer them. */
    public static String inboxNote(LibraryStore store, boolean writer) throws IOException {
        int whoWaits = writer ? FamilyIdentity.open(store).size() : 0;   // the page behind it is for those who may write
        List<FamilyNameQuestions.Question> names = writer ? FamilyNameQuestions.open(store) : List.of();
        String who = whoWaits == 0 ? "" : "<p><strong>" + whoWaits + (whoWaits == 1 ? " question about your family is" : " questions about your family are") + " waiting for you.</strong> "
                + "The library found web pages under your relatives' names and cannot tell which ones are really about them. It will not start their research until you answer. <a href=\"/who\">Answer now</a></p>";
        String naming = names.isEmpty() ? "" : "<p><strong>" + names.size() + (names.size() == 1 ? " question about names and families waits" : " questions about names and families wait") + " for you:</strong> "
                + Pages.esc(FamilyNameQuestions.counted(names)) + ". Only your family can say these, and each answer says what it will do before you give it. <a href=\"/who\">Answer now</a></p>";
        return who + naming;
    }
}
