package org.researchzosho.librarian.profiles;

import org.researchzosho.librarian.Profile;
import org.researchzosho.librarian.Vocabulary;

import java.util.List;

/**
 * The software field: questions about projects, libraries and tools. What it adds is the collections a web engine buries — a code
 * host has hundreds of millions of repositories and nobody browses it — and the way a project is judged: by whether it is alive, what it
 * is licensed under and what its own README and code say, never by its stars. A run of this field is offered the repository and package
 * collections; any other run is untouched.
 */
public final class SoftwareProfile implements Profile {

    @Override public String name() { return "software"; }

    @Override public String description() { return "projects, libraries and tools: repository and package search, judged by activity and licence, not stars"; }

    @Override public List<Vocabulary.Term> predicates() {
        return List.of(
                new Vocabulary.Term("depends-on", "depends on", List.of("requires", "built on", "uses the library"), ""),
                new Vocabulary.Term("implements", "implements", List.of("is an implementation of", "supports the protocol"), ""),
                new Vocabulary.Term("fork-of", "is a fork of", List.of("forked from"), ""),
                new Vocabulary.Term("maintained-by", "is maintained by", List.of("maintainer", "developed by"), ""),
                new Vocabulary.Term("licensed-under", "is licensed under", List.of("licence", "license"), ""),
                new Vocabulary.Term("written-in", "is written in", List.of("implemented in", "language"), ""));
    }

    /** Words that mean a software project. Not "library", "framework" or "package": those belong to every field (and this product is a library). */
    private static final List<String> WORDS = List.of(
            "github", "gitlab", "codeberg", "repositor", "open source", "open-source", "opensource", "source code", "npm package", "pypi", "crates.io",
            "self-hosted", "selfhosted", "オープンソース", "リポジトリ", "ソースコード", "开源", "開源", "오픈소스", "quelloffen", "logiciel libre", "código abierto");

    @Override public boolean applies(String question) {
        String q = Vocabulary.norm(question);
        for (String w : WORDS) if (q.contains(w)) return true;
        return false;
    }

    @Override public boolean wantsRecords() { return true; }

    @Override public boolean readsCode() { return true; }

    @Override public String register() {
        return "Judge a project by what it is today: its last push, its last release, how its issues are answered, its licence, and what its own "
                + "README and code say it does. Stars say how many people once looked, and a search ordered by them hides the small and the new: "
                + "search the collection ordered by recent activity as well, and read past the first page. A project's topics are doors to its neighbours: search again with topic:<one of them>. For each project note the date of its "
                + "last push, its licence, its language and the version you read, with the repository as the source. A project with no push in two "
                + "years is noted as inactive, with the date. A blog post or a list about a project points to it: read the project itself.";
    }

    @Override public String planRules() {
        return "This is a question about software projects. Split it by the kinds of project that could answer it and by ecosystem (the "
                + "language or the package index), and give one sub-question to the small and recent projects: created or pushed in the last "
                + "twelve months, found by recent activity and by topic rather than by stars.";
    }

    @Override public String criticRules() {
        return "For a question about software projects the evidence is enough when the candidates include recent and small projects as well as "
                + "the well-known ones, and each has its last push, its licence and what it actually does from its own README. A gap worth "
                + "another search names a kind of project, an ecosystem or a topic not yet searched.";
    }

    @Override public String writerRules() {
        return "Give the projects in a table: project, what it does, last push, licence, language, stars, why it fits the question. Order "
                + "it by fit and by how alive the project is, not by stars. Say which projects are inactive, and since when.";
    }
}
