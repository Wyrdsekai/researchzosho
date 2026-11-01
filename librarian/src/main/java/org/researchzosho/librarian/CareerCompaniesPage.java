package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.librarian.CareerCut.Cut;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The companies of the person's place (docs/DESIGN_CAREER.md, the company list): what the public records give on each, the model's
 * reading of it against what the person wants, and the person's reactions. Companies first; openings are one signal among the facts.
 */
public final class CareerCompaniesPage {
    private CareerCompaniesPage() { }

    public static String body(LibraryStore store, Career.Seeker s, boolean writer) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("<p class=\"k\"><a href=\"/career?seeker=").append(Pages.esc(s.slug())).append("\">").append(Pages.esc(s.name())).append("'s career page</a> · <a href=\"/career/openings?seeker=")
                .append(Pages.esc(s.slug())).append("\">openings</a></p>");
        Cut cut = CareerCut.kept(store, s);
        if (cut == null) cut = Cut.NONE;
        ObjectNode doc = CareerCompanies.kept(store, s);
        if (!doc.has("companies") || doc.path("companies").isEmpty()) {
            b.append("<p>The library has not gathered the employers of your place yet. ");
            if (cut.places().isEmpty()) b.append("It needs a place: decide where you work on <a href=\"/career?seeker=").append(Pages.esc(s.slug())).append("\">your career page</a>, and a search gathers the employers there from the public records.</p>");
            else b.append("A search gathers them from the public records, company registers, the lists of hospitals, schools and universities, the filings of money raised, and reads each against what you want.</p>");
            if (writer && !cut.places().isEmpty()) b.append(form(s, "refresh", "", "", "Search now"));
            return b.toString();
        }
        List<ObjectNode> standing = CareerCompanies.standing(doc, cut, CareerSearch.keepOut(store, s));   // on this copy, so every company's cut_by is today's
        int total = doc.path("companies").size();
        b.append("<p>Employers of <b>").append(Pages.esc(doc.path("place").asText(""))).append("</b> from the public records, refreshed ").append(Pages.esc(doc.path("refreshed").asText(""))).append(": ")
                .append(total).append(" known, <b>").append(standing.size()).append(" fit</b> what you decided, ").append(total - standing.size()).append(" set aside by it.</p>");
        if (doc.path("notes").isArray() && !doc.path("notes").isEmpty()) { b.append("<p class=\"k\">"); for (JsonNode n : doc.path("notes")) b.append(Pages.esc(n.asText())).append(". "); b.append("</p>"); }
        b.append("<p class=\"k\">Each company's facts come from the records named with them, dated. The model read each against what you want and says what fits, what goes against, what is unknown, and whether to approach; your likes and passes are what check it. ")
                .append("Like puts your word on it; keep puts it on the list the search reads for openings and cards.</p>");
        List<ObjectNode> yes = new ArrayList<>(), maybe = new ArrayList<>(), no = new ArrayList<>(), unread = new ArrayList<>(), liked = new ArrayList<>();
        for (ObjectNode e : standing) {
            if (e.path("reaction").asText("").equals("like")) { liked.add(e); continue; }
            String a = e.path("reading").path("approach").asText("");
            (a.equals("yes") ? yes : a.equals("maybe") ? maybe : a.equals("no") ? no : unread).add(e);
        }
        if (!liked.isEmpty()) { b.append("<h2>Liked</h2>"); for (ObjectNode e : liked) b.append(card(e, s, writer)); }
        b.append("<h2>To approach now</h2>");
        if (yes.isEmpty()) b.append("<p>None yet").append(unread.isEmpty() ? "." : ": " + unread.size() + " not read yet.").append("</p>");
        for (ObjectNode e : yes) b.append(card(e, s, writer));
        if (!maybe.isEmpty()) { b.append("<h2>Maybe: something you decided is unknown</h2>"); for (ObjectNode e : maybe) b.append(card(e, s, writer)); }
        if (!no.isEmpty()) {
            b.append("<details><summary>Read and set aside <span class=\"k\">").append(no.size()).append("</span></summary>");
            for (ObjectNode e : no) b.append(card(e, s, writer));
            b.append("</details>");
        }
        if (!unread.isEmpty()) {
            b.append("<details><summary>Not read yet <span class=\"k\">").append(unread.size()).append("</span></summary><ul>");
            for (ObjectNode e : unread) b.append("<li>").append(Pages.esc(CareerCompanies.facts(e))).append("</li>");
            b.append("</ul><p class=\"k\">A search reads more of them, sixty at a time.</p></details>");
        }
        List<ObjectNode> out = new ArrayList<>();
        doc.path("companies").properties().forEach(en -> { ObjectNode e = (ObjectNode) en.getValue(); if (!e.path("cut_by").asText("").isEmpty() || e.path("reaction").asText("").equals("pass")) out.add(e); });
        if (!out.isEmpty()) {
            b.append("<details><summary>Set aside by what you decided, or passed on <span class=\"k\">").append(out.size()).append("</span></summary><ul>");
            for (ObjectNode e : out) b.append("<li>").append(Pages.esc(e.path("name").asText(""))).append(" <span class=\"k\">").append(Pages.esc(e.path("reaction").asText("").equals("pass") ? "passed on" + (e.path("reaction_why").asText("").isEmpty() ? "" : ": " + e.path("reaction_why").asText()) : e.path("cut_by").asText(""))).append("</span>")
                    .append(writer && e.path("reaction").asText("").equals("pass") ? form(s, "react", e.path("name").asText(""), "", "undo") : "").append("</li>");
            b.append("</ul></details>");
        }
        if (writer) b.append("<p>").append(form(s, "refresh", "", "", "Search again")).append(" <span class=\"k\">A search gathers the records again when a week has passed, reads the companies not read yet, and files cards for those worth approaching.</span></p>");
        return b.toString();
    }

    static String card(ObjectNode e, Career.Seeker s, boolean writer) {
        String name = e.path("name").asText("");
        StringBuilder b = new StringBuilder("<div class=\"card\"><p><b>").append(Pages.esc(name)).append("</b>");
        if (!e.path("site").asText("").isEmpty()) b.append(" <a href=\"https://").append(Pages.esc(e.path("site").asText(""))).append("\" rel=\"noopener\" class=\"k\">").append(Pages.esc(e.path("site").asText(""))).append("</a>");
        b.append("</p><p class=\"k\">").append(Pages.esc(CareerCompanies.facts(e).replaceFirst("^[^;]*; ", ""))).append("</p>");
        JsonNode r = e.path("reading");
        if (r.isObject()) {
            if (!r.path("fits").asText("").isEmpty()) b.append("<p><b>Fits:</b> ").append(Pages.esc(r.path("fits").asText())).append("</p>");
            if (!r.path("against").asText("").isEmpty()) b.append("<p><b>Against:</b> ").append(Pages.esc(r.path("against").asText())).append("</p>");
            if (!r.path("unknown").asText("").isEmpty()) b.append("<p><b>Unknown:</b> ").append(Pages.esc(r.path("unknown").asText())).append("</p>");
            b.append("<p class=\"k\">Read on ").append(Pages.esc(r.path("at").asText(""))).append(".</p>");
        }
        if (e.path("reaction").asText("").equals("like")) b.append("<p class=\"k\">You liked it").append(e.path("reaction_why").asText("").isEmpty() ? "" : ": " + Pages.esc(e.path("reaction_why").asText())).append(".").append(writer ? form(s, "react", name, "", "undo") : "").append("</p>");
        else if (writer) b.append("<p>").append(reactForm(s, name)).append(" ").append(form(s, "keep", name, "", "Keep on the list")).append("</p>");
        return b.append("</div>").toString();
    }

    static String reactForm(Career.Seeker s, String name) {
        return "<form method=\"post\" action=\"/career/companies\" class=\"inline\">" + CareerOpeningsPage.hidden("op", "react") + CareerOpeningsPage.hidden("seeker", s.slug()) + CareerOpeningsPage.hidden("name", name)
                + "<input name=\"why\" placeholder=\"why, in a few words (optional)\" size=\"40\" aria-label=\"why\"> <button name=\"reaction\" value=\"like\">Like</button> <button name=\"reaction\" value=\"pass\">Pass</button></form>";
    }

    static String form(Career.Seeker s, String op, String name, String reaction, String label) {
        return "<form method=\"post\" action=\"/career/companies\" class=\"inline\">" + CareerOpeningsPage.hidden("op", op) + CareerOpeningsPage.hidden("seeker", s.slug()) + (name.isEmpty() ? "" : CareerOpeningsPage.hidden("name", name))
                + (op.equals("react") ? CareerOpeningsPage.hidden("reaction", reaction) : "") + "<button class=\"" + (label.equals("undo") ? "lnk" : "") + "\">" + Pages.esc(label) + "</button></form>";
    }
}
