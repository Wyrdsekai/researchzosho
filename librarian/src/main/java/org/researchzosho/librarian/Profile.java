package org.researchzosho.librarian;

import java.io.IOException;
import java.util.List;

/**
 * A profile is data plus a small adapter on top of the general core — never a fork of it. It declares
 * the predicates and node kinds its field uses, how to import and export that field's interchange
 * format, the register the cataloger reads when it works material of that kind, and any rule the
 * desk must honour (a living person's privacy, say). The core suite runs with no profile enabled and
 * every core feature still works; a profile can add, never change.
 *
 * <p>Enabled per library: {@code profiles: genealogy, science} in {@code catalog/library.md}. The
 * science profile is on by default because every field cites; nothing else is.
 */
public interface Profile {

    String name();

    String description();

    /** The predicates this profile declares, seeded into the graph's vocabulary when the profile is enabled. */
    List<Vocabulary.Term> predicates();

    /** Node kinds beyond the core's seven that this profile uses ("family" for a GEDCOM family record, say). */
    default List<String> nodeKinds() { return List.of(); }

    /** A paragraph the cataloger and the runner read when material of this kind is on the desk. */
    default String register() { return ""; }

    /** Extra command-line verbs: {@code researchzosho <name> <verb…>}. Return null when the verb is unknown. */
    default Integer cli(LibraryStore store, String[] args) throws Exception { return null; }

    /** One-line usage for the command's help. */
    default String usage() { return ""; }

    /** Called once when the profile is enabled on a library: seed vocabularies, create files. */
    default void enable(LibraryStore store) throws IOException {
        for (Vocabulary.Term t : predicates()) Graph.predicate(store, t.slug(), t.description(), t.also());
    }
}
