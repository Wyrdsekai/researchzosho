package org.researchzosho.librarian;

import java.io.IOException;

/**
 * The one point where what an account spends is asked about and told (0.8.0, stage 3). Before a use (a research run, a chat turn, a
 * translation, a night, a page added by its address) the account service asks {@link #mayStart}; the use starts only on a yes. After
 * it, the service tells {@link #spent} what it came to. The public program answers from the account's caps ({@link CapsSpending}); a
 * service run for pay answers from somewhere else, named in a setting. Whatever answers, an answer that does not come is a no.
 */
public interface Spending {
    /** A use as it is asked for: who, in which library, what kind, on which model entry, who pays, what it is estimated to cost. */
    record Use(String id, String account, String library, String kind, String entry, String payer, long estimateMicros, long estimateMinutes, String job) { }

    /** Yes or no; what the person is told; on a yes, the most the use may spend (dollars, minutes; -1 for no limit). */
    record Answer(boolean yes, String said, long ceilingMicros, long ceilingMinutes) {
        static Answer no(String said) { return new Answer(false, said, 0, 0); }
    }

    /** What a use came to: dollars, and the counts of each kind under it. */
    record Actual(long micros, long modelTokens, long searches, long fetches, boolean estimated) { }

    Answer mayStart(Use use) throws IOException;

    void spent(Use use, Actual actual) throws IOException;

    /**
     * What a use came to, told again: more was counted under it after it ended (a call that began before), and {@code actual} is its whole
     * now. Nothing by default.
     */
    default void spentAgain(Use use, Actual actual) throws IOException { }
}
