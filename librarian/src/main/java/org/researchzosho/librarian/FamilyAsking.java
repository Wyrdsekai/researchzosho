package org.researchzosho.librarian;

import org.researchzosho.Config;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Asking beside the reading. At a terminal, {@code genealogy read} hands the questions about names and families that a file, a page, a
 * Geni profile or a tree file raised to an asker that runs beside the reading, on a thread of its own, and the reading goes straight on to
 * the next file. The asker puts one question at a time, waits for an answer up to {@link #SETTING} seconds (60 unless set; 0 never asks
 * while reading), files an answer at once as the family's word, and when no answer comes it leaves the question open and puts the next.
 * Before it puts a question it checks that the question is still open: a later file, or an earlier answer, may have settled it. When the
 * reading is finished, the questions that got no answer while it went on are asked once more, the same way; the read then says how many
 * wait for {@code researchzosho genealogy who}. Enter alone leaves a question for that command, and it is not asked again. Where nobody
 * can answer (a script, a pipe, the service, MCP, the nightly research) nothing is asked and nothing waits: the questions are simply open.
 *
 * <p>While a question is on the screen, what the reading prints is held, and printed after the question is answered or its time runs out,
 * whole lines only: one lock is around everything written to the terminal. The asker's filing and the reading's filing go through one lock
 * too ({@link #FILING}), so an answer is never filed while a file is, and neither reads the library while the other writes it.
 *
 * <p>The wait never leaves a thread blocked on the terminal: it looks every {@link #POLL_MS} milliseconds whether a whole line has been
 * typed (a terminal hands the program a line only after Enter), and reads it only then. A thread blocked in a read would take the next line
 * typed, which may be meant for the prompt that comes after the reading.
 */
public final class FamilyAsking {

    private FamilyAsking() { }

    /** How many seconds a question asked while reading waits for an answer; in the config file {@code genealogy.ask.seconds}. 0: never ask while reading. */
    public static final String SETTING = "RESEARCHZOSHO_GENEALOGY_ASK_SECONDS";

    /** How often the wait looks whether a line was typed. */
    static final int POLL_MS = 150;

    /**
     * The one lock that filing goes through while a read asks beside it: the reading holds it while it files a file and works out the
     * questions the file raised, and the asker while it checks a question and files an answer. Never held while a model reads or a
     * question waits.
     */
    public static final ReentrantLock FILING = new ReentrantLock();

    /** Work done under {@link #FILING}. */
    @FunctionalInterface
    public interface Filing<T> { T run() throws IOException; }

    /** Does {@code f} under {@link #FILING}. */
    public static <T> T filing(Filing<T> f) throws IOException {
        FILING.lock();
        try { return f.run(); } finally { FILING.unlock(); }
    }

    /** The time a question asked while reading waits, in seconds; 0 when reading never asks. */
    public static int seconds() { return Math.max(0, Config.getInt(SETTING, 60)); }

    /** What one command's asking has come to: how many questions were put and answered, and how many that it raised still wait. */
    public static final class Session {
        private volatile int asked, answered, waiting;
        private Asker asker;

        public int asked() { return asked; }
        public int answered() { return answered; }
        /** The questions this command raised that are still open at its end, for the sitting. */
        public int waiting() { return waiting; }
    }

    /** Whether this command may ask while it reads: a person at the keyboard and a time above 0. */
    public static boolean on(Session s) { return s != null && Interaction.interactive() && seconds() > 0; }

    /** The codes of the questions open now, to tell afterwards which ones a file raised; empty, and nothing worked out, when this command does not ask. */
    public static Set<String> openNow(LibraryStore store, Session s) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        if (!on(s)) return out;
        for (FamilyNameQuestions.Question q : FamilyNameQuestions.open(store)) out.add(q.code());
        return out;
    }

    /**
     * After a file, a page, a Geni profile or a tree file is filed: the questions about {@code touched} (node ids; empty for any) that were
     * not open {@code before} are handed to the asker, which starts with the first of them; the reading goes on at once. {@code last}: nothing
     * more is read after this, so what the asker puts from now on is put after the reading. Nothing is asked unless {@link #on} holds.
     */
    public static void afterFiling(LibraryStore store, Set<String> touched, Set<String> before, Session s, boolean last) throws IOException {
        if (!on(s)) return;
        if (s.asker == null || s.asker.over()) s.asker = new Asker(store, s);
        s.asker.hand(touched, before, last);
    }

    /**
     * The reading is finished: the asker puts what it has not put yet, then once more each question that got no answer while the reading
     * went on, and gives the terminal back. What the read says at its end about the questions it raised that wait ({@link #summary}); ""
     * when none waits, when nothing was asked, or when this was said already.
     */
    public static String end(Session s) {
        if (s == null || s.asker == null || s.asker.over()) return "";
        s.asker.end();
        return summary(s);
    }

    /** The command ends early (an error): the asker stops at once, puts nothing more, and gives the terminal back. */
    public static void stop(Session s) { if (s != null && s.asker != null) s.asker.stop(); }

    /** The questions about these people (any, for none) that were not open before and are not in {@code skip}, in the order a sitting asks them. */
    private static List<FamilyNameQuestions.Question> raised(LibraryStore store, Set<String> scope, Set<String> before, Set<String> skip) throws IOException {
        List<FamilyNameQuestions.Question> qs = scope.isEmpty() ? FamilyNameQuestions.open(store) : FamilyNameQuestions.about(store, scope);
        List<FamilyNameQuestions.Question> out = new ArrayList<>();
        for (FamilyNameQuestions.Question q : FamilyNameQuestions.ordered(qs)) if (!before.contains(q.code()) && !skip.contains(q.code())) out.add(q);
        return out;
    }

    /** A question handed to the asker: its code, the people of the file that raised it (empty for any), what was open before that file, and whether this is its asking once more. */
    private record Pending(String code, Set<String> scope, Set<String> before, boolean onceMore) { }

    /** The asker of one command: a thread beside the reading, and the terminal it shares with the reading. */
    private static final class Asker {
        private final LibraryStore store;
        private final Session s;
        private final int secs = seconds();
        private final String teller = System.getProperty("user.name", "the owner of this library");
        /** The terminal, as it was before the asker held the reading's lines. */
        private final PrintStream out, err;
        /** What the reading prints while the asker runs. */
        private final Held heldOut, heldErr;
        private final PrintStream readingOut, readingErr;
        /** The one lock around what is written to the terminal; {@code onScreen} and {@code atLineStart} are kept under it. */
        private final Object screen = new Object();
        private boolean onScreen, atLineStart = true;
        // kept under the asker's own monitor
        private final Deque<Pending> queue = new ArrayDeque<>();
        /** Every question this command raised, by code: none is handed twice. */
        private final Set<String> known = new LinkedHashSet<>();
        /** The questions that got no answer while the reading went on, to be asked once more at its end. */
        private final List<Pending> again = new ArrayList<>();
        private boolean readingDone, ending, stopped, over;
        // the asker's thread only
        private boolean saidOnceMore, inputEnded;
        private final Thread thread;

        Asker(LibraryStore store, Session s) {
            this.store = store;
            this.s = s;
            out = System.out;
            err = System.err;
            heldOut = new Held(out);
            heldErr = new Held(err);
            readingOut = new PrintStream(heldOut, true, out.charset());
            readingErr = new PrintStream(heldErr, true, err.charset());
            System.setOut(readingOut);
            System.setErr(readingErr);
            thread = new Thread(this::run, "asking beside the reading");
            thread.setDaemon(true);
            thread.start();
        }

        synchronized boolean over() { return over; }

        /** The questions a file raised, worked out now, under the filing lock, and put in line; {@code last}: the reading is finished. */
        void hand(Set<String> touched, Set<String> before, boolean last) throws IOException {
            Set<String> scope = touched == null ? Set.of() : Set.copyOf(touched);
            Set<String> was = before == null ? Set.of() : Set.copyOf(before);
            Set<String> skip;
            synchronized (this) { skip = new HashSet<>(known); }
            List<FamilyNameQuestions.Question> raised = filing(() -> raised(store, scope, was, skip));
            synchronized (this) {
                for (FamilyNameQuestions.Question q : raised) if (known.add(q.code())) queue.add(new Pending(q.code(), scope, was, false));
                if (last) readingDone = true;
                notifyAll();
            }
        }

        /** The reading is finished: what is not put yet, then the questions to ask once more; waits for the asker, and gives the terminal back. */
        void end() {
            synchronized (this) {
                if (over) return;
                readingDone = true;
                ending = true;
                queue.addAll(again);   // after the ones not put yet
                again.clear();
                notifyAll();
            }
            try { thread.join(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); stop(); return; }
            giveBack();
            // how many of the questions this command raised are still open: those put off or answered are not
            try {
                Set<String> open = new HashSet<>();
                for (FamilyNameQuestions.Question q : filing(() -> FamilyNameQuestions.open(store))) open.add(q.code());
                int n = 0;
                synchronized (this) { for (String c : known) if (open.contains(c)) n++; }
                s.waiting = n;
            } catch (IOException e) { s.waiting = 0; }
        }

        /** Stops at once: nothing more is put. */
        void stop() {
            synchronized (this) {
                if (over) return;
                stopped = true;
                ending = true;
                notifyAll();
            }
            thread.interrupt();
            try { thread.join(10_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            giveBack();
        }

        /** What the reading printed and was held, all of it, and the terminal as it was before. */
        private void giveBack() {
            synchronized (screen) {
                heldOut.pass(true);
                heldErr.pass(true);
                out.flush();
                err.flush();
            }
            if (System.out == readingOut) System.setOut(out);
            if (System.err == readingErr) System.setErr(err);
            synchronized (this) { over = true; }
        }

        private void run() {
            try {
                while (true) {
                    Pending p;
                    synchronized (this) {
                        while (queue.isEmpty() && !ending) wait();
                        if (stopped || queue.isEmpty()) return;
                        p = queue.poll();
                    }
                    if (!inputEnded) put(p);
                }
            } catch (InterruptedException e) {
                // the command stopped early
            } catch (Exception e) {
                boolean early;
                synchronized (this) { early = stopped; }
                if (!early) say("The library could not ask its questions about names and families here (" + e.getMessage() + "). They wait for you: researchzosho genealogy who");
            } finally {
                synchronized (screen) {
                    if (onScreen && !atLineStart) out.println();
                    if (onScreen) atLineStart = true;
                    onScreen = false;
                    heldOut.pass(false);
                    heldErr.pass(false);
                }
            }
        }

        /** Whether the last question's time ran out: a line typed after that is not an answer to the next one. */
        private boolean late;

        /** One question: checked, put, waited for, and its answer filed; after an answer, the questions it raised about the same people come next. */
        private void put(Pending p) throws IOException, InterruptedException {
            List<FamilyNameQuestions.Question> openNow = filing(() -> p.scope().isEmpty() ? FamilyNameQuestions.open(store) : FamilyNameQuestions.about(store, p.scope()));
            FamilyNameQuestions.Question q = openNow.stream().filter(x -> x.code().equals(p.code())).findFirst().orElse(null);
            if (q == null) return;   // settled since it was raised
            // who is who first: a question another one waiting here would settle (whether two fathers are one person, before which of them is
            // the birth father) is put after that one
            for (FamilyNameQuestions.Question first : openNow) if (FamilyNameQuestions.settles(first, q) && after(first.code(), p)) return;
            // a line typed after the last question's time ran out was meant for that question: it never answers this one
            boolean dropped = false;
            if (late) { late = false; while (typed()) { if (Interaction.readLine() == null) break; dropped = true; } }
            if (dropped) say("What was typed after the last question's time ran out was not used, so it does not answer the next question.");
            boolean reading;
            synchronized (this) { reading = !readingDone; }
            synchronized (screen) {
                onScreen = true;
                if (!atLineStart) out.println();
                if (p.onceMore() && !saidOnceMore) {
                    saidOnceMore = true;
                    out.println("\nThe reading is finished. The library asks once more the questions that got no answer while it was reading.");
                }
                out.println();
                out.print(FamilyNameQuestions.shown(q));
                out.println("\n" + (FamilyNameQuestions.takesAYear(q) ? "Type the year, for example 1932, or the number of an answer, and press Enter." : "Type the number of your answer and press Enter.")
                        + " Press Enter alone to answer it later, with researchzosho genealogy who."
                        + (reading ? " The reading goes on while this question waits." : "") + " If no answer comes in " + secondsSaid(secs) + ", the question stays open.");
                out.print("Your answer: ");
                out.flush();
                atLineStart = false;
            }
            s.asked++;
            Typed t = lineWithin(secs);
            if (Thread.interrupted()) throw new InterruptedException();
            if (t.timedOut()) {
                late = true;
                boolean onceMore;
                synchronized (this) {
                    onceMore = !readingDone && !p.onceMore();
                    if (onceMore) again.add(new Pending(p.code(), p.scope(), p.before(), true));
                }
                done("\nNo answer came within " + secondsSaid(secs) + ". " + (onceMore
                        ? "The question stays open, and the library asks it once more when the reading is finished. The reading goes on."
                        : "The question waits for you: researchzosho genealogy who"));
                // a moment before the next question, so an answer typed just too late is not taken as the answer to the next one
                Thread.sleep(Math.min(3, secs) * 1000L);
                return;
            }
            if (t.line() == null) {
                inputEnded = true;
                done("\nNothing more can be typed here, so the library asks no more questions during this read. They wait for you: researchzosho genealogy who");
                return;
            }
            String a = t.line().strip();
            if (a.isEmpty()) { done("Left for later. It waits for you: researchzosho genealogy who"); return; }
            if (!a.equalsIgnoreCase("later") && FamilyNameQuestions.option(q, a) == null) {
                done("\"" + a + "\" is not one of the answers, so the question waits for you: researchzosho genealogy who");
                return;
            }
            // the answer that names somebody else in the library asks for the name, and asks again while it finds nobody
            FamilyNameQuestions.Option chosen = a.equalsIgnoreCase("later") ? null : FamilyNameQuestions.option(q, a);
            String typed = chosen != null && chosen.key().equals(FamilyNameQuestions.SOMEONE) ? name(q) : "";
            if (typed == null) return;
            String said;
            try {
                said = filing(() -> a.equalsIgnoreCase("later") ? FamilyNameQuestions.answer(store, q.code(), "later", "", teller)
                        : FamilyNameQuestions.answer(store, q.code(), a, a.matches("\\d{4}") ? a : "", teller, typed));
            } catch (IllegalArgumentException e) {
                // the reading may have settled the question while it waited: then it waits for nobody
                boolean open = filing(() -> stillOpen(q.code(), p.scope())) != null;
                done(e.getMessage() + (open ? " The question waits for you: researchzosho genealogy who" : ""));
                return;
            }
            if (!a.equalsIgnoreCase("later")) s.answered++;
            done(said);
            if (a.equalsIgnoreCase("later")) return;
            // an answer changes the evidence: the questions it raised about the same people come first. A file about anybody (a tree file)
            // stays about anybody
            Set<String> scope = new LinkedHashSet<>(p.scope());
            if (!scope.isEmpty()) scope.addAll(q.people());
            Set<String> skip;
            synchronized (this) { skip = new HashSet<>(known); }
            List<FamilyNameQuestions.Question> now = filing(() -> raised(store, scope, p.before(), skip));
            List<FamilyNameQuestions.Question> next = new ArrayList<>();
            for (FamilyNameQuestions.Question x : now) if (x.people().stream().anyMatch(q.people()::contains)) next.add(x);
            for (FamilyNameQuestions.Question x : now) if (!next.contains(x)) next.add(x);
            synchronized (this) {
                List<Pending> first = new ArrayList<>();
                for (FamilyNameQuestions.Question x : next) if (known.add(x.code())) first.add(new Pending(x.code(), Set.copyOf(scope), p.before(), false));
                for (int i = first.size() - 1; i >= 0; i--) queue.addFirst(first.get(i));
            }
        }

        /**
         * Puts {@code p} after the question {@code code}, which would settle it: when that one waits in line here it is moved to the front with
         * {@code p} right after it; when it waits to be asked once more, {@code p} joins it there; when it was open before this command and was
         * not put yet, it is put first all the same. False when it was put already and left open: then {@code p} is put now.
         */
        private synchronized boolean after(String code, Pending p) {
            for (Pending x : queue) if (x.code().equals(code)) { queue.remove(x); queue.addFirst(p); queue.addFirst(x); return true; }
            for (Pending x : again) if (x.code().equals(code)) { if (!p.onceMore() && !readingDone) { again.add(new Pending(p.code(), p.scope(), p.before(), true)); return true; } return false; }
            if (!known.add(code)) return false;
            queue.addFirst(p);
            queue.addFirst(new Pending(code, p.scope(), p.before(), p.onceMore()));
            return true;
        }

        /** The question with this code as it stands now, among those about these people (any, for none); null when it no longer waits. */
        private FamilyNameQuestions.Question stillOpen(String code, Set<String> scope) throws IOException {
            for (FamilyNameQuestions.Question q : scope.isEmpty() ? FamilyNameQuestions.open(store) : FamilyNameQuestions.about(store, scope))
                if (q.code().equals(code)) return q;
            return null;
        }

        /** The last line of a question, and the terminal is the reading's again: what it printed meanwhile comes now. */
        /**
         * The name typed for the answer that names somebody else in the library, asked again, with the reason, while it finds nobody; null,
         * said, when no name comes in time or Enter alone is pressed, and the question waits.
         */
        private String name(FamilyNameQuestions.Question q) throws IOException {
            String self = q.people().isEmpty() ? "" : q.people().get(0);
            while (true) {
                synchronized (screen) { out.print("Type the name of the person as your library writes it, and press Enter: "); out.flush(); atLineStart = false; }
                Typed t = lineWithin(secs);
                if (t.timedOut()) late = true;
                if (t.timedOut() || t.line() == null || t.line().isBlank()) { done((t.timedOut() ? "\n" : "") + "No name came, so the question waits for you: researchzosho genealogy who"); return null; }
                String typed = t.line().strip();
                try { filing(() -> FamilyNameQuestions.typedPerson(store, self, typed)); return typed; }
                catch (IllegalArgumentException e) { synchronized (screen) { out.println(e.getMessage()); atLineStart = true; } }
            }
        }

        private void done(String line) {
            synchronized (screen) {
                out.println(line);
                out.flush();
                atLineStart = true;
                onScreen = false;
                heldOut.pass(false);
                heldErr.pass(false);
            }
        }

        /** A line of the asker's own, when no question is on the screen. */
        private void say(String line) {
            synchronized (screen) {
                if (!atLineStart) out.println();
                out.println("\n" + line);
                out.flush();
                atLineStart = true;
            }
        }

        /**
         * What the reading prints while the asker runs: straight to the terminal when no question is on the screen, whole lines at a time;
         * held while one is, and printed after it.
         */
        private final class Held extends OutputStream {
            private final PrintStream to;
            private final ByteArrayOutputStream kept = new ByteArrayOutputStream();

            Held(PrintStream to) { this.to = to; }

            @Override public void write(int b) { write(new byte[]{(byte) b}, 0, 1); }

            @Override public void write(byte[] b, int off, int len) {
                synchronized (screen) {
                    kept.write(b, off, len);
                    if (!onScreen) pass(false);
                }
            }

            /** A flush lets a line that is not finished out too (a prompt), unless a question is on the screen. */
            @Override public void flush() {
                synchronized (screen) {
                    if (!onScreen) pass(true);
                    to.flush();
                }
            }

            /** Writes what is kept: its whole lines, or all of it. Under {@code screen}. */
            void pass(boolean all) {
                byte[] b = kept.toByteArray();
                int n = b.length;
                if (!all) while (n > 0 && b[n - 1] != '\n') n--;
                if (n == 0) return;
                to.write(b, 0, n);
                to.flush();
                atLineStart = b[n - 1] == '\n';
                kept.reset();
                kept.write(b, n, b.length - n);
            }
        }
    }

    /** A line typed within the time, or none: {@code timedOut} when the time ran out; a null line at the end of the input. */
    record Typed(String line, boolean timedOut) { }

    /** Waits up to {@code seconds} for a whole line, looking every {@link #POLL_MS} milliseconds, and reads it only when one is there. */
    static Typed lineWithin(int seconds) throws IOException {
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        while (true) {
            if (typed()) return new Typed(Interaction.readLine(), false);
            if (System.nanoTime() >= end) return new Typed(null, true);
            try { Thread.sleep(POLL_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return new Typed(null, true); }
        }
    }

    /** Whether a line is there to read: the test reader's, else the terminal's (which a terminal reports only after Enter). */
    private static boolean typed() {
        BufferedReader in = Interaction.INPUT;
        try { return in != null ? in.ready() : System.in.available() > 0; } catch (IOException e) { return false; }
    }

    /** "1 second", "60 seconds". */
    static String secondsSaid(int n) { return n == 1 ? "1 second" : n + " seconds"; }

    /** What a read says at its end about the questions it raised that wait, with the command that answers them; "" when none waits. */
    public static String summary(Session s) {
        if (s == null || s.waiting() == 0) return "";
        int n = s.waiting();
        return (n == 1 ? "One question" : n + " questions") + " about names and families that this read raised " + (n == 1 ? "waits" : "wait") + " for you. "
                + (n == 1 ? "To answer it: researchzosho genealogy who. It shows the answers you can give and what each one does."
                          : "To answer them one at a time: researchzosho genealogy who. It shows the answers you can give and what each one does.");
    }

}
