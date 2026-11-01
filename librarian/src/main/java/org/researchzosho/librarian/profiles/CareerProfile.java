package org.researchzosho.librarian.profiles;

import org.researchzosho.librarian.Career;
import org.researchzosho.librarian.CareerInterview;
import org.researchzosho.librarian.CareerPage;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.librarian.CareerCompanies;
import org.researchzosho.librarian.CareerCompaniesPage;
import org.researchzosho.librarian.CareerCompany;
import org.researchzosho.librarian.CareerCut;
import org.researchzosho.librarian.CareerFind;
import org.researchzosho.librarian.CareerOpeningsPage;
import org.researchzosho.librarian.CareerSearch;
import org.researchzosho.librarian.CareerShape;
import org.researchzosho.librarian.CareerWatch;
import org.researchzosho.librarian.LibraryStore;
import org.researchzosho.librarian.Uploads;
import org.researchzosho.librarian.Patrons;
import org.researchzosho.librarian.Profile;
import org.researchzosho.librarian.Vocabulary;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The career field (docs/DESIGN_CAREER.md): the side of a person looking for work. Built: their record from what they hand over, the
 * library's reading of their working life, what they want with each topic decided, a guess or open, and the interview, one question at a
 * time in either chat and all at once on the Career page. Not yet: the companies' pages, the new postings, the fit reading. The field
 * joins no research run and names no relations of its own, so an ordinary library is the same with it on or off.
 */
public final class CareerProfile implements Profile {

    @Override public String name() { return "career"; }

    @Override public String description() { return "a person looking for work: their working life read from a resume or a LinkedIn file, and what they want next, before any search"; }

    @Override public List<Vocabulary.Term> predicates() { return List.of(); }

    @Override public boolean joinsOnlyWhenAsked() { return true; }

    @Override public Path workFolder(LibraryStore store) { return Career.dir(store); }

    @Override public List<String> pagePaths() { return List.of("/career", "/career/openings", "/career/companies"); }

    @Override public List<Link> menu() { return List.of(new Link("/career", "Career", false, "⚒")); }

    @Override public List<String> chatCommandWords() { return List.of("/career"); }

    @Override public boolean chatHolds(LibraryStore store, String chat) throws IOException { return CareerInterview.holder(store, chat) != null; }

    @Override public Page page(LibraryStore store, Patrons.Patron patron, String path, String method, Map<String, String> query, Map<String, String> form) throws IOException {
        if (!path.equals("/career") && !path.equals("/career/openings") && !path.equals("/career/companies")) return null;
        Patrons.check(store, patron, Patrons.Level.read);
        boolean writer;
        try { Patrons.check(store, patron, Patrons.Level.write); writer = true; } catch (RuntimeException e) { writer = false; }
        if (path.equals("/career/openings")) return openingsPage(store, patron, method, query, form, writer);
        if (path.equals("/career/companies")) return companiesPage(store, patron, method, query, form, writer);
        if ("POST".equals(method)) {
            Patrons.check(store, patron, Patrons.Level.write);
            String op = form.getOrDefault("op", "");
            Career.Seeker s = Career.seeker(store, form.getOrDefault("seeker", ""));
            try {
                switch (op) {
                    case "provide" -> {   // the page keeps words, and a file the browser posted; a path typed as words is never read; a name given opens that person's page, new or known
                        String name = form.getOrDefault("name", "").strip(), who = !name.isEmpty() ? name : s == null ? "" : s.name();
                        String text = form.getOrDefault("text", "").strip(), file = form.getOrDefault("file.path", "").strip();
                        try {
                            if (text.isEmpty() && file.isEmpty()) throw new IllegalArgumentException("Write something, or choose a file.");
                            String target = who;   // the name given wins; the file, when there is one, settles the name the words then go to
                            if (!file.isEmpty()) { s = Career.provide(store, file, who, true).seeker(); target = s.name(); }   // the upload's own copy, under its own name: read as the command line reads a file
                            if (!text.isEmpty()) { Career.Provided told = Career.provide(store, text, target, false); s = told.seeker(); if (told.kind().equals("told")) CareerInterview.settleTold(store, s, text); }   // the words read at once for what they decide; the page's "lately" says what was set
                        } finally { Uploads.drop(form); }
                    }
                    case "reading" -> { if (s != null && !Career.reading(store, s).isBlank()) Career.wants(store, s); }
                    case "search" -> { if (s != null) CareerFind.file(store, s, patron.did(), "career-search", form.getOrDefault("vary", "")); }   // the button at the top of the person's page; the page then says the search is on
                    case "want" -> { if (s != null) Career.setWant(store, s, form.getOrDefault("dimension", ""), form.getOrDefault("state", ""), form.getOrDefault("want", "")); }
                    case "company" -> { if (s != null) CareerSearch.addCompany(store, s, form.getOrDefault("words", ""), ""); }
                    case "keep-out" -> { if (s != null) CareerSearch.addKeepOut(store, s, form.getOrDefault("words", ""), ""); }
                    case "remove" -> { if (s != null) CareerSearch.remove(store, s, form.getOrDefault("list", ""), form.getOrDefault("words", "")); }
                    case "resettle" -> { if (s != null) CareerInterview.resettle(store, s); }
                    case "answers" -> {
                        if (s != null) {
                            Map<String, String> byN = new LinkedHashMap<>();
                            for (Map.Entry<String, String> e : form.entrySet()) if (e.getKey().startsWith("a.")) byN.put(e.getKey().substring(2), e.getValue());
                            CareerInterview.saveAnswers(store, s, byN);
                        }
                    }
                    default -> { }
                }
            } catch (IllegalArgumentException e) { return new Page("Career", "<p>" + e.getMessage().replace("&", "&amp;").replace("<", "&lt;") + "</p><p><a href=\"/career\">Back</a></p>", false, null); }
            return new Page("Career", "", false, "/career" + (s == null ? "" : "?seeker=" + s.slug()) + (op.equals("answers") ? "#questions" : ""));
        }
        String who = query.getOrDefault("seeker", "").strip();
        Career.Seeker s = who.isEmpty() ? null : Career.seeker(store, who);   // no name: the front door, the people on the shelves, even when there is only one
        return new Page(s == null ? "Career" : s.name() + " · Career", CareerPage.body(store, s, writer), false, null);
    }

    /** The openings list: what the last search found, and the forms that react, keep or drop a company, change the cut, and search. */
    private static Page openingsPage(LibraryStore store, Patrons.Patron patron, String method, Map<String, String> query, Map<String, String> form, boolean writer) throws IOException {
        if ("POST".equals(method)) {
            Patrons.check(store, patron, Patrons.Level.write);
            Career.Seeker s = Career.seeker(store, form.getOrDefault("seeker", ""));
            if (s == null) return new Page("Openings", "", false, "/career");
            String op = form.getOrDefault("op", ""), anchor = "";
            try {
                switch (op) {
                    case "search" -> CareerFind.file(store, s, patron.did(), "career-search", form.getOrDefault("vary", ""));
                    case "read" -> CareerFind.file(store, s, patron.did(), "career-read");
                    case "shape" -> { CareerShape.tell(store, s, form.getOrDefault("words", "")); }
                    case "react" -> { CareerFind.react(store, s, form.getOrDefault("id", ""), form.getOrDefault("reaction", ""), form.getOrDefault("why", "")); anchor = "#o-" + form.getOrDefault("id", ""); }
                    case "company" -> CareerFind.companyState(store, s, form.getOrDefault("name", ""), form.getOrDefault("state", ""));
                    case "keep-out" -> CareerFind.keepOut(store, s, form.getOrDefault("name", ""));
                    case "card", "dossier" -> {
                        List<ObjectNode> cs = CareerFind.companies(store, s);
                        ObjectNode c = null; for (ObjectNode x : cs) if (x.path("name").asText().equalsIgnoreCase(form.getOrDefault("name", "").strip())) c = x;
                        if (c == null) throw new IllegalArgumentException("There is no company called " + form.getOrDefault("name", "") + " on the list.");
                        CareerCompany.file(store, s, c, op.equals("dossier"), patron.did());
                        CareerFind.saveCompanies(store, s, cs);
                    }
                    case "cut" -> {
                        Map<String, String> f = new LinkedHashMap<>();
                        for (String k : List.of("titles", "titles_also", "titles_not", "areas", "places", "remote", "remote_where", "max_people", "min_people", "prefer_titles", "prefer_places")) if (form.containsKey(k)) f.put(k, form.get(k));
                        CareerCut.set(store, s, f);
                    }
                    case "remake" -> CareerCut.make(store, s);
                    case "watch" -> CareerWatch.set(store, s, "true".equals(form.getOrDefault("on", "")));
                    default -> { }
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                return new Page("Openings", "<p>" + e.getMessage().replace("&", "&amp;").replace("<", "&lt;") + "</p><p><a href=\"/career/openings?seeker=" + s.slug() + "\">Back</a></p>", false, null);
            }
            return new Page("Openings", "", false, "/career/openings?seeker=" + s.slug() + anchor);
        }
        Career.Seeker s = Career.seeker(store, query.getOrDefault("seeker", ""));
        if (s == null) return new Page("Openings", "", false, "/career");
        String body = CareerOpeningsPage.body(store, s, writer, query.getOrDefault("run", ""));   // a past search by its record, else the openings as they stand
        if (writer) CareerWatch.looked(store, s);   // what arrived is shown once more now, and is no longer new after
        return new Page(s.name() + " · Openings", body, false, null);
    }

    /** The companies of the place: what the public records give, the model's reading of each against the wants, and the person's reactions. */
    private static Page companiesPage(LibraryStore store, Patrons.Patron patron, String method, Map<String, String> query, Map<String, String> form, boolean writer) throws IOException {
        if ("POST".equals(method)) {
            Patrons.check(store, patron, Patrons.Level.write);
            Career.Seeker s = Career.seeker(store, form.getOrDefault("seeker", ""));
            if (s == null) return new Page("Companies", "", false, "/career");
            String op = form.getOrDefault("op", "");
            try {
                switch (op) {
                    case "react" -> CareerCompanies.react(store, s, form.getOrDefault("name", ""), form.getOrDefault("reaction", ""), form.getOrDefault("why", ""));
                    case "keep" -> CareerFind.companyState(store, s, form.getOrDefault("name", ""), "kept");
                    case "refresh" -> CareerFind.file(store, s, patron.did(), "career-search");
                    default -> { }
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                return new Page("Companies", "<p>" + e.getMessage().replace("&", "&amp;").replace("<", "&lt;") + "</p><p><a href=\"/career/companies?seeker=" + s.slug() + "\">Back</a></p>", false, null);
            }
            return new Page("Companies", "", false, "/career/companies?seeker=" + s.slug());
        }
        Career.Seeker s = Career.seeker(store, query.getOrDefault("seeker", ""));
        if (s == null) return new Page("Companies", "", false, "/career");
        return new Page(s.name() + " · Companies", CareerCompaniesPage.body(store, s, writer), false, null);
    }

    /** At the terminal: the reply printed, after a word that the slow ones take a while. */
    @Override public boolean chatCommand(LibraryStore store, String line) {
        String l = line.strip();
        if (!(l.equals("/career") || l.startsWith("/career "))) return false;
        String verb = l.length() > 7 ? l.substring(7).strip().split("\\s+", 2)[0] : "";
        if (verb.equals("reading")) say("The model is reading the facts. This takes a minute or two.");
        try {
            String r = chatReply(store, Patrons.Patron.PERSON, "terminal", l);
            if (r != null) for (String x : r.split("\n")) say(x);
        } catch (IOException e) { say("That did not work: " + e.getMessage()); }
        return true;
    }

    /** The career commands in any chat, and the answers to an interview this chat holds. */
    @Override public String chatReply(LibraryStore store, Patrons.Patron patron, String chat, String line) throws IOException {
        if (line == null) return null;
        String l = line.strip();
        if (!l.startsWith("/")) return CareerInterview.take(store, chat, l);   // a plain line: an answer, when this chat holds an interview
        if (!(l.equals("/career") || l.startsWith("/career "))) return null;
        String rest = l.substring(7).strip();
        String[] head = rest.split("\\s+", 2);
        String verb = head[0], tail = head.length > 1 ? head[1].strip() : "";
        boolean writes = !List.of("read", "evidence", "openings", "companies", "").contains(verb) && !((verb.equals("company") || verb.equals("keep-out") || verb.equals("cut") || verb.equals("watch")) && tail.isEmpty());
        if (writes && !patron.person() && !Patrons.mayWrite(store, patron)) return "That changes the record, so it is for those who may write to this library. /career read shows it.";
        try {
            switch (verb) {
                case "provide" -> {
                    if (tail.isEmpty()) return "To hand over a file, type /career provide and the path to it: a resume as a PDF, a Word file or plain text, or a LinkedIn profile saved as a .json file. You can also type words after /career provide, about what you want next.";
                    Career.Provided p = Career.provide(store, tail, "", patron.person());   // a file only for the library's owner
                    String said = Career.providedText(store, p);
                    if (p.kind().equals("told") && p.added() > 0) { String set = CareerInterview.settleTold(store, p.seeker(), tail); if (!set.isEmpty()) said = said + "\n" + set; }
                    return p.added() > 0 && !p.kind().equals("told") ? said + "\nNext, type /career reading to have the library write its reading of your working life. It takes a minute or two." : said;
                }
                case "reading" -> {
                    Career.Seeker s = who(store, tail); if (s == null) return nobody(store, tail);
                    if (Career.reading(store, s).isBlank()) return "The model did not answer, so there is no reading yet. Check that the model server is running, then type /career reading again.";
                    Career.wants(store, s);
                    return Career.text(store, s, "/career");
                }
                case "wants" -> { Career.Seeker s = who(store, tail); if (s == null) return nobody(store, tail); Career.wants(store, s); return Career.text(store, s, "/career"); }
                case "evidence" -> { Career.Seeker s = who(store, tail); if (s == null) return nobody(store, tail); return Career.evidenceText(store, s); }
                case "read", "" -> { Career.Seeker s = who(store, tail); if (s == null) return nobody(store, tail); return Career.text(store, s, "/career"); }
                case "interview" -> { Career.Seeker s = who(store, tail); if (s == null) return nobody(store, tail); return CareerInterview.start(store, s, chat); }
                case "answers" -> { Career.Seeker s = who(store, tail); if (s == null) return nobody(store, tail); return CareerInterview.resettle(store, s) + "\n" + Career.text(store, s, "/career"); }
                case "company" -> {
                    if (tail.isEmpty()) return "To have the search look at a company, type /career company and its name. /career company drop and the name takes it off.";
                    Career.Seeker s = who(store, ""); if (s == null) return nobody(store, "");
                    if (tail.startsWith("drop ")) return CareerSearch.remove(store, s, "companies", tail.substring(5)) ? "The search no longer looks at " + tail.substring(5).strip() + " for you." : "That company is not on the list.";
                    return CareerSearch.addCompany(store, s, tail, "") ? "The search will look at " + tail + "." : CareerSearch.kept(store, s, "companies", tail) + " is on the list already.";
                }
                case "keep-out" -> {
                    if (tail.isEmpty()) return "To keep something out of the search, a company, an industry, a kind of work or a condition, type /career keep-out and the words. /career keep-out drop and the words takes it off.";
                    Career.Seeker s = who(store, ""); if (s == null) return nobody(store, "");
                    if (tail.startsWith("drop ")) return CareerSearch.remove(store, s, "keep_out", tail.substring(5)) ? "The search no longer keeps out " + tail.substring(5).strip() + "." : "That is not kept out.";
                    return CareerSearch.addKeepOut(store, s, tail, "") ? "The search will keep out " + tail + "." : CareerSearch.kept(store, s, "keep_out", tail) + " is kept out already.";
                }
                case "search" -> {   // /career search, or /career search <a change for this search only>; with several people, the name first: /career search Jane Doe: without the pets field
                    String name = tail, vary = "";
                    if (tail.contains(":")) { name = tail.substring(0, tail.indexOf(':')).strip(); vary = tail.substring(tail.indexOf(':') + 1).strip(); }
                    Career.Seeker s = who(store, name);
                    if (s == null && !tail.contains(":") && Career.seekers(store).size() == 1) { s = Career.seekers(store).get(0); vary = tail; }
                    if (s == null) return nobody(store, name);
                    return CareerFind.file(store, s, patron.did(), "career-search", vary);
                }
                case "more" -> { Career.Seeker s = who(store, tail); if (s == null) return nobody(store, tail); return CareerFind.file(store, s, patron.did(), "career-read"); }
                case "openings" -> {
                    Career.Seeker s = who(store, tail); if (s == null) return nobody(store, tail);
                    String fresh = CareerWatch.text(store, s), all = CareerFind.text(store, s, "/career", 10);
                    CareerWatch.looked(store, s);
                    return fresh.isEmpty() ? all : fresh + "\n" + all;
                }
                case "watch" -> {
                    Career.Seeker s = who(store, ""); if (s == null) return nobody(store, "");
                    if (tail.equals("on") || tail.equals("off")) return CareerWatch.set(store, s, tail.equals("on"));
                    return (CareerWatch.on(store, s) ? "The nightly watch is on." : "The nightly watch is off.") + " /career watch on or /career watch off changes it.";
                }
                case "like", "pass" -> {
                    String[] w = tail.split("\\s+", 2);
                    if (tail.isEmpty()) return "Type /career " + verb + ", then the opening's short name in brackets from /career openings, or a company's name from /career companies, then why in a few words.";
                    Career.Seeker s = who(store, ""); if (s == null) return nobody(store, "");
                    if (w[0].replace("[", "").replace("]", "").matches("[0-9a-f]{4,10}")) {
                        try { return CareerFind.react(store, s, w[0], verb, w.length > 1 ? w[1] : ""); } catch (IllegalArgumentException noOpening) { /* a company may spell like an opening's short name */ }
                    }
                    int sep = tail.indexOf(" - "); String name = sep > 0 ? tail.substring(0, sep).strip() : tail, why = sep > 0 ? tail.substring(sep + 3).strip() : "";
                    try { return CareerCompanies.react(store, s, name, verb, why); } catch (IllegalArgumentException e) { return e.getMessage() + " For an opening, give its short name in brackets; for a company, its name, then \" - \" and why."; }
                }
                case "shape" -> {   // how seats are filled for them; /career shape <words> keeps their own word on it
                    Career.Seeker s = who(store, ""); if (s == null) return nobody(store, "");
                    return tail.isEmpty() ? CareerShape.text(CareerShape.current(store, s)) : CareerShape.tell(store, s, tail);
                }
                case "companies" -> { Career.Seeker s = who(store, tail); if (s == null) return nobody(store, tail); return CareerCompanies.text(store, s, CareerCut.current(store, s), "/career", 8); }
                case "card", "dossier" -> {
                    if (tail.isEmpty()) return "Type /career " + verb + " and a company's name from the list: " + (verb.equals("card") ? "a short dossier of it by the library's research, about " + CareerCompany.CARD_MINUTES + " minutes." : "the full dossier, the deep run.");
                    Career.Seeker s = who(store, ""); if (s == null) return nobody(store, "");
                    List<ObjectNode> cs = CareerFind.companies(store, s);
                    ObjectNode c = null; for (ObjectNode x : cs) if (x.path("name").asText().equalsIgnoreCase(tail)) c = x;
                    if (c == null) return "There is no company called " + tail + " on the list. /career keep " + tail + " puts it there.";
                    String said = CareerCompany.file(store, s, c, verb.equals("dossier"), patron.did());
                    CareerFind.saveCompanies(store, s, cs);
                    return said;
                }
                case "keep", "drop" -> {
                    if (tail.isEmpty()) return "Type /career " + verb + " and a company's name.";
                    Career.Seeker s = who(store, ""); if (s == null) return nobody(store, "");
                    return CareerFind.companyState(store, s, tail, verb.equals("keep") ? "kept" : "dropped");
                }
                case "cut" -> {
                    Career.Seeker s = who(store, ""); if (s == null) return nobody(store, "");
                    if (tail.isEmpty()) { CareerCut.Cut c = CareerCut.kept(store, s); return c == null ? "There is no cut yet: the first search makes it from what you decided." : "The cut: " + CareerCut.summary(c) + ".\nTo change it, type /career cut, then titles, titles-also, titles-not, areas, places, remote, remote-where, prefer-titles or prefer-places, then the words separated by commas. /career cut again has the library make it again from what you want."; }
                    if (tail.equals("again")) return "The cut, made again from what you want: " + CareerCut.summary(CareerCut.make(store, s)) + ".";
                    String[] w = tail.split("\\s+", 2);
                    String field = w[0].replace('-', '_');
                    if (!List.of("titles", "titles_also", "titles_not", "areas", "places", "remote", "remote_where", "max_people", "min_people", "prefer_titles", "prefer_places").contains(field)) return "The cut's parts are titles, titles-also, titles-not, areas, places, remote, max-people, min-people, remote-where, prefer-titles and prefer-places.";
                    return "The cut, as you set it: " + CareerCut.summary(CareerCut.set(store, s, Map.of(field, w.length > 1 ? w[1] : ""))) + ".";
                }
                case "want" -> {
                    String[] p = tail.split("\\s+");
                    int at = -1; for (int i = 1; i < p.length; i++) if (!Career.state(p[i]).isEmpty()) { at = i; break; }
                    if (at < 1) return "To set a topic yourself, type /career want, then the topic, then decided, guess or open, then what you want in your own words. For example: /career want remote decided yes, fully remote. The topics are: " + String.join(", ", Career.DIMENSIONS) + ".";
                    Career.Seeker s = who(store, ""); if (s == null) return nobody(store, "");
                    String dim = String.join(" ", Arrays.copyOfRange(p, 0, at)), want = String.join(" ", Arrays.copyOfRange(p, at + 1, p.length));
                    return "Saved. " + Career.line(Career.setWant(store, s, dim, p[at], want));
                }
                default -> { return "That is not one of the career commands. They are: /career provide, to hand over a resume, a LinkedIn file or words about what you want; /career reading, to have the library write its reading of your working life; /career read, to show the reading and what you want; /career interview, to answer the reading's questions one at a time; /career answers, to have the library read your answers again for what they settle; /career company, to have the search look at a company; /career keep-out, to keep a company, an industry or a condition out of it; /career search, to look for openings and gather the companies of your place; /career companies, to read the companies of your place against what you want; /career openings, to read what the search found; /career like and /career pass, to say what you think of an opening; /career keep and /career drop, for a company on the list; /career card and /career dossier, to have the library research a company, briefly or in full; /career cut, to see or change which openings pass; /career more, to have the library read more of them; /career watch on, to have the library look every night; /career wants, to draft what you want again; /career want, to set one topic yourself; /career evidence, to list the facts the reading rests on."; }
            }
        } catch (IllegalArgumentException e) { return e.getMessage(); }
    }

    private static Career.Seeker who(LibraryStore store, String name) throws IOException { return Career.seeker(store, name); }

    /** Why there is nobody to act on: none held, or no such name, or more than one and no name given. */
    private static String nobody(LibraryStore store, String name) throws IOException {
        List<Career.Seeker> all = Career.seekers(store);
        if (all.isEmpty()) return "Nobody's working life is kept here yet. Start with /career provide and the path to a resume or a LinkedIn file.";
        if (!name.isBlank()) return "There is nobody called " + name + " here. The people here are: " + Career.and(all.stream().map(Career.Seeker::name).toList()) + ".";
        return "This library keeps more than one person's working life: " + Career.and(all.stream().map(Career.Seeker::name).toList()) + ". Add the name, for example /career read " + all.get(0).name() + ".";
    }

    private static void say(String s) { System.out.println("  " + s); }

    @Override public String chatCommandHelp() { return "/career (your working life and what you want next: provide, reading, read, interview, answers, company, keep-out, search, companies, openings, like, pass, keep, drop, card, dossier, cut, more, watch, wants, want, evidence)"; }

    /** The inbox's one line for the openings the nightly watch found since the person last looked. */
    @Override public String inboxNote(LibraryStore store, boolean writer) throws IOException { return CareerWatch.inboxNote(store); }
}
