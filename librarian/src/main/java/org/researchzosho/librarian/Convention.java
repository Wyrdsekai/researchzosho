package org.researchzosho.librarian;

import java.util.List;

/**
 * A convention is how a claim must be stated in a subject, carried by any goal: a law claim carries its jurisdiction and the date the
 * law stood; a science claim cites the record, names the preprint version and the dataset; a patent claim carries the number, the
 * claims, the dates, the family and the status. A home question that meets a zoning dispute carries the law convention for that part.
 */
public interface Convention {

    /** "law", "science", "patents". */
    String name();

    /** The words the brain reads: what the convention asks of a claim and when it is carried. */
    String words();

    /** The rules every claim under this convention follows, for the writer and the critic. */
    String claimRules();

    /** Whether a question carries this convention, with the reason. */
    Goal.Applies appliesTo(String question);

    /** The fields that back this convention during the transition. */
    default List<Profile> profiles() { return List.of(); }
}
