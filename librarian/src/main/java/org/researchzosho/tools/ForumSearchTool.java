package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.librarian.Fence;
import org.researchzosho.librarian.SourceRules;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code forum_search}: discussion threads read in full, for a research run — Reddit through the Arctic Shift archive, the Stack Exchange
 * sites through their API (see {@link Forums}). kind=threads lists the threads of a place for some words, best first; kind=read gives one
 * thread with its answers or comments. What people wrote is fenced as data for the model; the same text, unfenced, is what the command prints.
 */
public final class ForumSearchTool implements Tool {

    public static final int MOST = 25;
    static final int MOST_CALLS = 40, POST_CHARS = 2500, REPLY_CHARS = 700, EXCERPT_CHARS = 200;
    private final AtomicInteger calls = new AtomicInteger();

    @Override public String name() { return "forum_search"; }

    @Override public String description() {
        return "Discussion threads, read in full: what people wrote to each other, with the votes. For what people say and do — their experience, their practice, "
                + "the mood of a trade — never for a fact: a date, a number, a name or a claim met here is a lead to confirm in a primary source, and a claim resting only on a "
                + "forum is filed as such and stays a draft for the person. site=reddit with subreddit=<name> reads a subreddit through "
                + "the Arctic Shift archive, a copy of Reddit minutes to hours behind (Reddit's own pages are never read); site=workplace, softwareengineering, stackoverflow, "
                + "serverfault, superuser, askubuntu or any other Stack Exchange site's name reads that site through its API. kind=threads: the threads for the words, best "
                + "first, each with its id, date, score, number of replies, author, the opening words and the address. kind=read with id=<the id from threads, or the "
                + "thread's address>: the opening post and its answers or comments, best first, each with the author, score and date; on a Stack Exchange site the "
                + "accepted answer is marked. A thread is one person's experience at a time and the votes are the crowd's reading of it; weigh it, never count it, "
                + "quote people as people, and cite the thread's address (Stack Exchange text is CC BY-SA: the address and the author's name).";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode().put("type", "object");
        ObjectNode props = p.putObject("properties");
        ObjectNode kind = props.putObject("kind").put("type", "string").put("description", "threads | read");
        kind.withArray("enum").add("threads").add("read");
        props.putObject("site").put("type", "string").put("description", "reddit, or a Stack Exchange site: workplace, softwareengineering, stackoverflow, serverfault, superuser, askubuntu, security, dba, ux, academia, … (the name before .stackexchange.com)");
        props.putObject("subreddit").put("type", "string").put("description", "site=reddit: the subreddit to read, without r/ (the archive searches one at a time)");
        props.putObject("query").put("type", "string").put("description", "threads: the words; on Reddit matched in the title or the opening post, on a Stack Exchange site searched as the site's search does");
        props.putObject("id").put("type", "string").put("description", "read: the thread's id from a threads call, or its address");
        props.putObject("days").put("type", "integer").put("description", "threads on Reddit: how many days back (default 730)");
        props.putObject("limit").put("type", "integer").put("description", "threads: how many, up to " + MOST + " (default 10)");
        p.putArray("required").add("kind").add("site");
        return p;
    }

    @Override public String execute(JsonNode args) {
        if (calls.incrementAndGet() > MOST_CALLS) return "ERROR: forum_search has been called " + MOST_CALLS + " times in this run, which is its limit. Work with what was read.";
        String kind = args.path("kind").asText("threads").toLowerCase(Locale.ROOT), site = args.path("site").asText("").strip();
        String query = args.path("query").asText("").strip(), id = args.path("id").asText("").strip();
        int limit = Math.max(1, Math.min(MOST, args.path("limit").asInt(10))), days = Math.max(1, args.path("days").asInt(730));
        if (site.isEmpty()) return "ERROR: site is needed: reddit, or a Stack Exchange site's name.";
        boolean reddit = Forums.isReddit(site);
        if (reddit) {
            if (!Forums.redditArchiveOn()) return "Reddit is pointed at and never read on this library (the setting reddit_archive is off): search the web with site:reddit.com and cite the addresses instead.";
            if (SourceRules.live().refused("https://www.reddit.com/")) return "The person refused reddit.com as a source, so it is not read.";
        } else {
            String s = Forums.stackSite(site);
            if (s.isEmpty()) return "ERROR: site is reddit, or a Stack Exchange site's name such as workplace or softwareengineering.";
            if (SourceRules.live().refused("https://" + s + ".stackexchange.com/") || SourceRules.live().refused("https://" + s + ".com/")) return "The person refused " + s + " as a source, so it is not read.";
        }
        String fenceLabel = kind.equals("read") ? "FORUM THREAD" : "FORUM THREADS";
        try {
            String text;
            if (kind.equals("read")) {
                if (id.isEmpty()) return "ERROR: id is needed for kind=read: the thread's id from a threads call, or its address.";
                text = readText(reddit ? Forums.redditThread(id) : Forums.stackThread(site, id), reddit);
            } else {
                String subreddit = reddit ? (args.path("subreddit").asText("").isBlank() ? site : args.path("subreddit").asText("")) : "";
                List<Forums.Topic> ts = reddit ? Forums.redditThreads(subreddit, query, days, limit) : Forums.stackThreads(site, query, limit);
                text = threadsText(ts, reddit ? "r/" + Forums.subredditName(subreddit) : Forums.stackSite(site) + ".stackexchange.com", query, reddit, days);
            }
            return Fence.wrap(fenceLabel, text) + "\n" + Fence.rule(fenceLabel) + quotaNote(reddit);
        } catch (IllegalArgumentException e) { return "ERROR: " + e.getMessage() + ".";
        } catch (Exception e) { return (reddit ? "The Arctic Shift archive" : "The Stack Exchange API") + " did not answer (" + e.getMessage() + ")."; }
    }

    public static String quotaNote(boolean reddit) {
        int left = Forums.stackQuotaLeft();
        return !reddit && left >= 0 && left < 50 ? "\nThe Stack Exchange API allows this library " + left + " more call(s) today" + (Forums.stackKeyed() ? "." : "; a free key (the setting stackexchange_key) raises the day's allowance from 300 to 10,000.") : "";
    }

    /** The threads as sentences, for the model and for the person alike. */
    public static String threadsText(List<Forums.Topic> ts, String where, String words, boolean reddit, int days) {
        String how = reddit ? Forums.ARCHIVE_NOTE : Forums.STACK_NOTE;
        if (ts.isEmpty()) return "No thread on " + where + (words.isEmpty() ? "" : " for \"" + words + "\"") + (reddit ? " in the last " + days + " days" : "") + ", " + how + ".";
        StringBuilder b = new StringBuilder(ts.size() + " thread(s) on " + where + (words.isEmpty() ? "" : " for \"" + words + "\"") + (reddit ? " from the last " + days + " days" : "") + ", best first, " + how + ":\n");
        for (Forums.Topic t : ts) {
            b.append("- [").append(t.id()).append("] ").append(t.date()).append(", score ").append(t.score()).append(", ").append(t.replies()).append(t.replies() == 1 ? " reply" : " replies")
             .append(t.author().isEmpty() ? "" : ", by " + Shops.clean(t.author())).append(": ").append(Shops.clean(t.title()))
             .append(t.text().isEmpty() ? "" : " — " + Shops.clean(cut(t.text(), EXCERPT_CHARS))).append(" — ").append(t.url()).append('\n');
        }
        return b.toString().strip();
    }

    /** One thread as sentences: the opening post, then the replies best first, each reply to a reply set in from it. */
    public static String readText(Forums.Read r, boolean reddit) {
        Forums.Topic t = r.topic();
        StringBuilder b = new StringBuilder("Thread [" + t.id() + "] on " + t.site() + (t.author().isEmpty() ? "" : ", by " + Shops.clean(t.author())) + ", " + t.date() + ", score " + t.score() + ", " + t.replies() + (t.replies() == 1 ? " reply" : " replies")
                + ", " + (reddit ? Forums.ARCHIVE_NOTE : Forums.STACK_NOTE) + " — " + t.url() + "\n");
        b.append(Shops.clean(t.title())).append('\n');
        if (!t.text().isEmpty()) b.append(cut(t.text(), POST_CHARS).strip()).append('\n');
        if (r.replies().isEmpty()) { b.append("No reply is held for it."); return b.toString(); }
        b.append("Replies, best first (").append(r.replies().size()).append(" shown of ").append(Math.max(t.replies(), r.replies().size())).append("):\n");
        for (Forums.Reply x : r.replies()) {
            b.append("  ".repeat(Math.min(x.depth(), 6))).append("- ").append(x.author().isEmpty() ? "someone" : Shops.clean(x.author())).append(", score ").append(x.score()).append(x.date().isEmpty() ? "" : ", " + x.date())
             .append(x.accepted() ? ", the accepted answer" : "").append(x.kind().equals("comment") ? ", a comment on the question" : "").append(": ").append(Shops.clean(cut(x.text(), REPLY_CHARS))).append('\n');
        }
        return b.toString().strip();
    }

    static String cut(String s, int most) { return s == null ? "" : s.length() <= most ? s : s.substring(0, most).strip() + "…"; }
}
