package org.researchzosho.librarian;

import java.util.List;

/**
 * A goal is what the person wants done: find a home, find work, understand a person or an organisation, buy a thing, assess prior art,
 * know what the law says, build the family, choose something to read, watch or listen to, learn a craft, or, the generic goal, answer the
 * question. A goal says when it applies, with a reason the brain weighs rather than a gate that decides; which instruments it brings to
 * the table; how a run of it plans, reviews and writes; the shape its report takes beyond the basis every report has; and what it
 * checks by which instrument. During the transition the fields that back a goal are its {@link #profiles}: their hooks keep working.
 */
public interface Goal {

    /** Whether a goal applies to a question, with the reason either way: a suggestion the brain weighs, never a gate. */
    record Applies(boolean yes, String why) {
        public static Applies yes(String why) { return new Applies(true, why); }
        public static Applies no(String why) { return new Applies(false, why); }
    }

    /** The goal's name on the table: "home", "work", "dossier", "buy", "prior-art", "what-the-law-says", "family", "learn-a-craft", "choose", "generic". */
    String name();

    /** The words the brain reads to choose it: what it is for, when it pays, one literal example of a question that is its own. Short, for a small model too. */
    String words();

    /** Whether the question is this goal's, with the reason. */
    Applies appliesTo(String question);

    /** The grouped instruments this goal brings to the table ("homes", "records", "board", "dossier", "who", "look"); none for the generic goal. */
    List<String> instruments();

    /** How a run of this goal splits the work; added to the planner's instruction. "" for the basis alone. */
    String planRules();

    /** What the reviewer checks before calling the evidence enough; added to the critic's instruction. */
    String criticRules();

    /** The sections and the care the write-up needs; added to the writer's instruction. */
    String writerRules();

    /** The report's shape beyond the basis, in words for the writer: tiers of fits with a card each, a dossier's sections, prior art. */
    String shape();

    /** What to check by which instrument, in words for the brain: a feature one sees is checked with look, never read off a description. */
    String decisionRules();

    /** The fields that back this goal during the transition; none for the generic goal. */
    default List<Profile> profiles() { return List.of(); }

    /** Whether the goal's rules may join a run by the question's words. False for a module, whose rules join only when somebody asks. */
    default boolean joinsByWords() { return true; }
}
