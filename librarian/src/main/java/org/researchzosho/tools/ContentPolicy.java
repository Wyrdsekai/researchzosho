package org.researchzosho.tools;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.researchzosho.drive.ContentJudge;
import org.researchzosho.librarian.LibraryStore;

/**
 * What one run, or one address the person gave, lets in, and who checks the pages it fetches. ResearchZosho does not decide for people
 * what they may research; these are the protections the owner chose (decided 2026-09-23):
 *
 * <ul>
 *   <li>Left out by default, let in for one question's run when the person says yes to it: pornography, and gore ({@link #EXPLICIT}: the
 *       site list before a fetch, the page check after it); step-by-step instructions for making a weapon or an explosive, making an
 *       illegal drug, or running working exploit code against a system ({@link #HOWTO}: the page check).</li>
 *   <li>Always dropped, whatever the person said: sexual content involving a child, in a page or a picture. Never saved; only the fact
 *       that something was left out is written down.</li>
 *   <li>An address the person gave themselves (add, a reading list, bookmarks) is their own act: only the always-dropped check applies.</li>
 * </ul>
 *
 * <p>A policy belongs to one run: Fetch and the tools are shared by concurrent runs, so each carries its run's policy. What was left out
 * is kept here, by address and category and nothing else, for the run's log and its report.
 */
public final class ContentPolicy {

    /** The categories a person can let in for one question's run, as the protocol's {@code allow} names them. */
    public static final String EXPLICIT = "explicit", HOWTO = "howto";
    /** Not a category: the person's yes to researching a question that reads as them asking about harming themselves. */
    public static final String SELF_HARM = "self-harm";
    /** Every name {@code allow} takes. */
    public static final List<String> ALLOW_NAMES = List.of(EXPLICIT, HOWTO, SELF_HARM);

    /**
     * One address left out: the category it was left out as, in words a person reads, and {@code requested}, the address asked for when a
     * redirect led from it to {@code url} ("" otherwise). Nothing of the content.
     */
    public record LeftOut(String url, String category, String requested) {
        public LeftOut(String url, String category) { this(url, category, ""); }
        public String line() { return "left out: " + category + " — " + url + (requested.isEmpty() ? "" : " (asked for as " + requested + ")"); }
    }

    private final Set<String> allow;
    private final boolean person;
    private final ContentJudge judge;
    private final LibraryStore store;
    private final List<LeftOut> leftOut = Collections.synchronizedList(new ArrayList<>());
    private volatile Consumer<String> log = line -> { };

    private ContentPolicy(Collection<String> allow, boolean person, ContentJudge judge, LibraryStore store) {
        Set<String> a = new LinkedHashSet<>();
        if (allow != null) for (String x : allow) if (x != null && !x.isBlank()) a.add(x.strip().toLowerCase(Locale.ROOT));
        this.allow = Collections.unmodifiableSet(a);
        this.person = person;
        this.judge = judge;
        this.store = store;
    }

    /**
     * A research run's policy: {@code allow} what its person let in for this question ({@link #EXPLICIT}, {@link #HOWTO}); {@code judge}
     * the run's model; {@code store} where its pages are kept (null: the configured library).
     */
    public static ContentPolicy run(Collection<String> allow, ContentJudge judge, LibraryStore store) { return new ContentPolicy(allow, false, judge, store); }

    /** The default for a fetch made outside a run: nothing let in, the library's configured model checking. */
    public static ContentPolicy defaults() { return new ContentPolicy(List.of(), false, null, null); }

    /** An address the person gave: only the always-dropped check; {@code judge} null is the library's configured model. */
    public static ContentPolicy person(ContentJudge judge) { return new ContentPolicy(List.of(), true, judge, null); }

    /** What this run lets in; empty for the default. */
    public Set<String> allow() { return allow; }
    public boolean lets(String category) { return allow.contains(category); }
    public boolean person() { return person; }

    /** Where the run's pages are kept; null for the configured library. */
    public LibraryStore store() { return store; }

    /** The judge the checks ask; the library's configured model when none was given. */
    public ContentJudge judge() { return judge != null ? judge : ContentJudge.configured(); }

    /** Which lists a fetch under this policy is checked against. */
    public Fetch.Policy fetchPolicy() { return person ? Fetch.Policy.PERSON : lets(EXPLICIT) ? Fetch.Policy.SITE_LIST_OFF : Fetch.Policy.DEFAULT; }

    /** The questions a page fetched under this policy is asked: the default categories it has not let in, then the always-dropped one. */
    public List<PageCheck.Category> categories() {
        List<PageCheck.Category> out = new ArrayList<>();
        if (!person && !lets(EXPLICIT)) out.add(PageCheck.Category.EXPLICIT);
        if (!person && !lets(HOWTO)) out.add(PageCheck.Category.HOWTO);
        out.add(PageCheck.Category.CHILD);
        return out;
    }

    // ---- verdicts already reached ----

    /** What this run's check made of a page, by its address and its text: the category it was left out as, or "" for kept. */
    private final Map<String, String> verdicts = new ConcurrentHashMap<>();

    /** The check's verdict on this address with this text, when this run reached one before: the category, "" for kept; null when not. */
    public String verdict(String url, String text) { return verdicts.get(key(url, text)); }

    /** A verdict the check reached (null: kept), kept for the rest of the run; an unanswered check is not kept, so it is asked again. */
    public void verdict(String url, String text, String leftOutAs) { verdicts.put(key(url, text), leftOutAs == null ? "" : leftOutAs); }

    private static String key(String url, String text) {
        String u = url == null ? "" : Fetch.canonical(url.strip());
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            return u + "\t" + HexFormat.of().formatHex(d, 0, 12);
        } catch (NoSuchAlgorithmException e) { return u + "\t" + (text == null ? 0 : text.hashCode()); }
    }

    // ---- a check that cannot run ----

    /** After this many pages in a row that no model could check, a run stops: its model answers, but the check on its server does not. */
    public static final int CANNOT_CHECK_AFTER = 3;
    private final AtomicInteger unjudgedInARow = new AtomicInteger();
    private volatile String lastWhy = "";

    /** One page's check, for the count of pages in a row that no model could check: a judged one starts the count again. */
    public void checked(ContentJudge.Reading r) {
        if (r == null) return;
        if (r.judged()) { unjudgedInARow.set(0); return; }
        unjudgedInARow.incrementAndGet();
        if (r.why() != null && !r.why().isBlank()) lastWhy = r.why();
    }

    /** Whether the check could not run on the last {@link #CANNOT_CHECK_AFTER} pages in a row. */
    public boolean cannotCheck() { return unjudgedInARow.get() >= CANNOT_CHECK_AFTER; }

    /** The plain statement a run that stops for it ends with: what happened, why, and what to set. */
    public String cannotCheckStatement() {
        return "The research stopped, because the page check cannot run on this model server. The library checks every page a run reads before the model sees it, "
                + "and the model did not answer the check's yes-or-no question for the last " + CANNOT_CHECK_AFTER + " pages, although it answers the research itself, "
                + "so every page would have been left out and nothing read. "
                + (lastWhy.isBlank() ? "" : "What the server did: " + lastWhy + ". ")
                + "What to set: if the server refuses a temperature, set RESEARCHZOSHO_TEMP=none; if the model thinks before it answers and its server ignores enable_thinking, "
                + "set RESEARCHZOSHO_DRIVE_TEMPLATE_KWARGS to the setting that turns its thinking off on that server; if a hosted service filters the check's question, "
                + "use a model server that answers it. Then send the question again.";
    }

    /** Where each address left out is said as it happens: the run's log. */
    public ContentPolicy log(Consumer<String> sink) { this.log = sink == null ? line -> { } : sink; return this; }

    /** An address left out, as {@code category}: kept for the report, said in the log. Nothing of its content. */
    public void leftOut(String url, String category) { leftOut(url, category, null); }

    /**
     * The same, where {@code requested} is the address asked for: a page reached through a redirect is written down under both, so a note
     * that cites the address asked for is found as resting on it.
     */
    public void leftOut(String url, String category, String requested) {
        String asked = requested == null || requested.isBlank() || Fetch.canonical(requested.strip()).equals(Fetch.canonical(url == null ? "" : url.strip())) ? "" : requested.strip();
        LeftOut l = new LeftOut(url, category, asked);
        synchronized (leftOut) { if (leftOut.contains(l)) return; leftOut.add(l); }
        try { log.accept(l.line()); } catch (RuntimeException ignored) { }
    }

    /** Everything left out so far, in order. */
    public List<LeftOut> leftOut() { synchronized (leftOut) { return List.copyOf(leftOut); } }

    /** Whether this address was left out under this policy. */
    public boolean wasLeftOut(String url) {
        if (url == null) return false;
        String c = Fetch.canonical(url.strip());
        synchronized (leftOut) { for (LeftOut l : leftOut) if (Fetch.canonical(l.url()).equals(c) || (!l.requested().isEmpty() && Fetch.canonical(l.requested()).equals(c))) return true; }
        return false;
    }
}
