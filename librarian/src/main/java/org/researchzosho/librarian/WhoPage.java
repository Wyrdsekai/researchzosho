package org.researchzosho.librarian;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * The page "Who is who": one question at a time. First the questions about names and families (who a person written by a family name
 * alone is, whether two entries are one person, how and when a name changed), each answer a button that says what it will do; then one
 * person of the family at a time, with the people the web shows under their name, and the buttons that say which of them is the relative.
 * A plain form, like the rest of the library's pages. Leave it at any point: what was answered is kept, and what still waits is listed
 * under the question being asked.
 */
final class WhoPage {
    private WhoPage() { }

    /** {@code show}: "pick" asks for a ticked entry; "names-<code>" shows that question about names; "done:<sentence>" says what the last answer did. */
    static String body(LibraryStore store, Patrons.Patron patron, String person, String show) throws IOException {
        Pages.writersOnly(store, patron);   // the page acts on the library: answers change claims and the research
        List<FamilyIdentity.Question> all = FamilyIdentity.all(store);
        List<FamilyNameQuestions.Question> names = FamilyNameQuestions.ordered(FamilyNameQuestions.open(store));
        Graph g = FamilyPeople.view(store);
        FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
        Function<String, String> heading = p -> { String id = g.nodeIdOf(p); return g.node(id) == null ? p : idx.heading(id); };
        StringBuilder b = new StringBuilder();
        if (show.startsWith("done:") && show.length() > 5) b.append("<p><strong>").append(Pages.esc(show.substring(5))).append("</strong></p>");
        // a question about names first, unless a person's web question is asked for
        FamilyNameQuestions.Question nq = null;
        if (show.startsWith("names-")) nq = names.stream().filter(x -> x.code().equals(show.substring(6))).findFirst().orElse(null);
        else if (person.isBlank() && !names.isEmpty()) nq = names.get(0);
        if (nq != null) {
            b.append("<p class=\"k\">Some things about names and families only your family can say: who a person written only by a family name is, whether two entries are one person, how and when a name changed. "
                    + "The library worked out what the sources say; the answer is yours. Each answer says what it will do before you give it, and how to take it back.</p>");
            b.append(namesQuestion(nq, names));
            b.append(namesList(names, nq));
            if (!all.isEmpty()) b.append(rest(all, null, heading));
            return b.toString();
        }
        if (!names.isEmpty()) b.append(namesList(names, null));
        if (all.isEmpty()) return b.append("<p>The library has not searched the web for anyone in your family yet. This happens at the start of a family search. In a terminal, type <code>researchzosho genealogy research</code>. To do only the web search, type <code>researchzosho genealogy who --find</code>.</p>").toString();
        List<FamilyIdentity.Question> open = all.stream().filter(FamilyIdentity.Question::open).toList();
        FamilyIdentity.Question q = person.isBlank() ? (open.isEmpty() ? null : open.get(0)) : all.stream().filter(x -> x.person().equals(person)).findFirst().orElse(null);
        b.append("<p class=\"k\">The library searched the web for each person in your family by name. Many people share the same name, so it cannot tell which search results are really about your relative. You can, so it asks you here. "
                + "Your answer is used in the research for that person: it starts from the pages you confirm and leaves out the other people with the same name. "
                + "You can stop at any time and come back later. Your answers are saved.</p>");
        if (q == null) b.append("<p><strong>There are no questions waiting for you.</strong> To choose whom to research, type this in a terminal: <code>researchzosho genealogy research</code>. It lists the people the library can research, the ones you answered for among them, and asks which to start with.</p>");
        else b.append(one(store, q, open, show, heading.apply(q.person())));
        b.append(rest(all, q, heading));
        return b.toString();
    }

    /** One question about names and families, with a button for each answer and the words of what it does. */
    private static String namesQuestion(FamilyNameQuestions.Question q, List<FamilyNameQuestions.Question> names) {
        StringBuilder b = new StringBuilder("<h2>").append(Pages.esc(FamilyNameQuestions.HEADINGS.getOrDefault(q.kind(), "A question about names").charAt(0) + FamilyNameQuestions.HEADINGS.getOrDefault(q.kind(), "A question about names").substring(1).toLowerCase(Locale.ROOT))).append("</h2>");
        int at = names.indexOf(q);
        if (at >= 0) b.append("<p class=\"k\">Question ").append(at + 1).append(" of ").append(names.size()).append(" about names and families.</p>");
        b.append("<p>").append(Pages.esc(q.text())).append("</p>");
        for (String f : q.findings()) b.append(" <a href=\"/entry/").append(Pages.esc(f)).append("\">").append(Pages.esc(f.replaceFirst("^(F-\\d+).*", "$1"))).append("</a>");
        // each answer a form of its own: Enter in the year field then sends the answer that takes the year, never the first button of the list
        b.append("<ul style=\"list-style:none;padding-left:0\">");
        for (FamilyNameQuestions.Option o : q.options()) {
            b.append("<li style=\"margin:.5em 0\"><form method=\"post\" action=\"/who\" style=\"margin:0\"><input type=\"hidden\" name=\"kind\" value=\"names\"><input type=\"hidden\" name=\"code\" value=\"").append(Pages.esc(q.code())).append("\">");
            if (o.key().equals("year")) b.append("<label>Year <input name=\"year\" size=\"6\" inputmode=\"numeric\" style=\"font:inherit;padding:.3em .5em\"></label> ");
            if (o.key().equals(FamilyNameQuestions.SOMEONE)) b.append("<label>Name <input name=\"name\" size=\"24\" style=\"font:inherit;padding:.3em .5em\"></label> ");
            b.append("<button name=\"choice\" value=\"").append(Pages.esc(o.key())).append("\">").append(Pages.esc(o.says())).append("</button> ").append(Pages.esc(o.does())).append("</form></li>");
        }
        return b.append("</ul>").toString();
    }

    /** The questions about names and families that wait, each a link to it. */
    private static String namesList(List<FamilyNameQuestions.Question> names, FamilyNameQuestions.Question shown) {
        if (names.isEmpty()) return "";
        StringBuilder b = new StringBuilder("<h2>Questions about names and families</h2><p class=\"k\">").append(names.size()).append(names.size() == 1 ? " question waits: " : " questions wait: ")
                .append(Pages.esc(FamilyNameQuestions.counted(names))).append(".</p><ul>");
        for (FamilyNameQuestions.Question q : names) {
            String first = q.text().replaceFirst("(?s)^(.{0,140}?[.?])\\s.*$", "$1");
            b.append("<li>").append(q == shown ? "<strong>" : "").append("<a href=\"/who?show=names-").append(Pages.esc(q.code())).append("\">").append(Pages.esc(first)).append("</a>").append(q == shown ? "</strong>" : "").append("</li>");
        }
        return b.append("</ul>").toString();
    }

    private static String one(LibraryStore store, FamilyIdentity.Question q, List<FamilyIdentity.Question> open, String show, String heading) {
        StringBuilder b = new StringBuilder();
        int at = open.indexOf(q);
        b.append("<h2>").append(Pages.esc(heading)).append("</h2>");
        if (at >= 0) b.append("<p class=\"k\">Question ").append(at + 1).append(" of ").append(open.size()).append(".</p>");
        else b.append("<p class=\"k\">Your earlier answer for this person: ").append(Pages.esc(state(q))).append(". You can change it here.</p>");
        b.append("<p>The library searched for ").append(Pages.esc(String.join(" and ", q.searched().stream().map(s -> "\"" + s + "\"").toList())))
         .append(". <a href=\"/tree?focus=").append(URLEncoder.encode(q.person(), StandardCharsets.UTF_8)).append("\">See this person in your family tree</a></p>");
        if (show.equals("pick")) b.append("<p><strong>Please tick the entry that is your relative first, then press the button.</strong></p>");
        b.append("<form method=\"post\" action=\"/who\"><input type=\"hidden\" name=\"person\" value=\"").append(Pages.esc(q.person())).append("\">");
        if (q.candidates().isEmpty()) b.append("<p>The search found no web pages for this name.</p>");
        for (FamilyIdentity.Candidate c : q.candidates()) {
            b.append("<div style=\"border:1px solid #8884;border-radius:6px;padding:.6em .9em;margin:.7em 0\"><label style=\"font-weight:600\"><input type=\"checkbox\" name=\"c").append(Pages.esc(c.id())).append("\" value=\"on\"")
             .append(c.said().equals("yes") ? " checked" : "").append("> ").append(Pages.esc(c.id())).append(". ").append(Pages.esc(c.what())).append("</label>");
            if (!c.relatives().isEmpty()) b.append("<div>These pages name ").append(c.relatives().size() == 1 ? "a relative" : "relatives").append(" your library has for this person: <strong>").append(Pages.esc(String.join(", ", c.relatives()))).append("</strong></div>");
            if (!c.others().isEmpty()) b.append("<div>These pages match these ").append(c.relatives().isEmpty() ? "" : "other ").append("facts from your library: <strong>").append(Pages.esc(String.join(", ", c.others()))).append("</strong></div>");
            if (c.said().equals("source")) b.append("<div><strong>This is your relative:</strong> your library read this person's facts from these pages.</div>");
            b.append("<ul style=\"margin:.3em 0 0\">");
            for (FamilyIdentity.Page p : c.pages().stream().limit(FamilyIdentity.PAGES_SHOWN).toList()) {
                b.append("<li><a href=\"").append(Pages.esc(p.url())).append("\" target=\"_blank\" rel=\"noopener noreferrer\">").append(Pages.esc(p.title().isBlank() ? p.url() : p.title())).append("</a> <span class=\"k\">")
                 .append(Pages.esc(FamilyIdentity.host(p.url()))).append("</span>").append(p.snippet().isBlank() ? "" : "<div class=\"k\">" + Pages.esc(p.snippet()) + "</div>").append("</li>");
            }
            if (c.pages().size() > FamilyIdentity.PAGES_SHOWN) b.append("<li class=\"k\">and ").append(c.pages().size() - FamilyIdentity.PAGES_SHOWN).append(" more page(s)</li>");
            b.append("</ul></div>");
        }
        b.append("<p>Tick the entry that is your relative. If two entries are the same person, tick both. Links open in a new tab.</p>");
        // each answer says what it does before it is given
        String who = Pages.esc(q.person());
        b.append("<ul style=\"list-style:none;padding-left:0\">")
         .append("<li><button name=\"do\" value=\"confirmed\">The ticked entry is my relative</button> The research for ").append(who).append(" starts from the ticked pages, and the other entries are taken as other people of the same name.</li>")
         .append("<li><button name=\"do\" value=\"none\">None of these is my relative</button> The research for ").append(who).append(" is given these pages as pages about other people, with nothing from them to go into the answer.</li>")
         .append("<li><button name=\"do\" value=\"unsure\">I do not know yet</button> The library still searches for ").append(who).append(", but does not take a page that only matches the name as being about your relative. ")
         .append(who).append(" is then listed below as skipped for now, and is not asked about again by itself: to answer later, open ").append(who).append(" from that list.</li></ul>");
        b.append("<p><label>Tell the library something about this person, for example their job, where they live, or a school they went to. This helps the next search.<br>"
                + "<input name=\"told\" style=\"width:100%;font:inherit;padding:.4em .6em\" placeholder=\"for example: she is a bridge engineer in Osaka and wrote a book about it\"></label> <button name=\"do\" value=\"told\">Save</button></p>");
        if (!q.told().isEmpty()) { b.append("<p class=\"k\">What you have told the library about this person:</p><ul>"); q.told().forEach(t -> b.append("<li>").append(Pages.esc(t)).append("</li>")); b.append("</ul>"); }
        b.append("</form>");
        return b.toString();
    }

    private static String rest(List<FamilyIdentity.Question> all, FamilyIdentity.Question shown, Function<String, String> heading) {
        StringBuilder b = new StringBuilder("<h2>Everyone the library searched for on the web</h2><ul>");
        for (FamilyIdentity.Question q : all) {
            b.append("<li>").append(q == shown ? "<strong>" : "").append("<a href=\"/who?person=").append(URLEncoder.encode(q.person(), StandardCharsets.UTF_8)).append("\">").append(Pages.esc(heading.apply(q.person()))).append("</a>")
             .append(q == shown ? "</strong>" : "").append(" <span class=\"k\">").append(Pages.esc(state(q))).append("</span></li>");
        }
        return b.append("</ul>").toString();
    }

    static String state(FamilyIdentity.Question q) {
        return switch (q.state()) {
            case "confirmed" -> "confirmed: " + String.join("; ", q.yes().stream().map(FamilyIdentity.Candidate::what).toList());
            case "none" -> "you said: none of the " + q.candidates().size() + " results is this person";
            case "unsure" -> "skipped for now";
            case "nobody" -> "no web pages found";
            default -> "waiting for your answer (" + q.candidates().size() + " possible matches)";
        };
    }
}
