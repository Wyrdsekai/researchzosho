package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.librarian.Fence;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code archive_search}: the web archives and the Internet Archive, for a research run. One tool with a {@code kind}, so that the
 * model has one description to read: an archived copy of a page, a page's history, the pages under a dead site, the Internet Archive's
 * texts (catalogue and full text), its television news, its films, Wikipedia's page history, and the heritage catalogues that need a
 * key. The run decides which to use; a copy that replaces a dead page is found by {@code web_fetch} itself, without this tool.
 */
public final class ArchiveSearchTool implements Tool {

    static final int MOST = 20, MOST_CALLS = 16;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile Fetch.Policy policy = Fetch.Policy.DEFAULT;

    public ArchiveSearchTool policy(Fetch.Policy p) { this.policy = p == null ? Fetch.Policy.DEFAULT : p; return this; }

    @Override public String name() { return "archive_search"; }

    @Override public String description() {
        return "The web archives and the Internet Archive. kind=copy: the saved copy of a page (query = its address) nearest a date, to web_fetch when the "
                + "live page is gone or changed. kind=history: when a page changed over the years. kind=site: the pages an archive holds under a site "
                + "or path that no longer exists. kind=texts: scanned books and documents by catalogue and by their full text; kind=tv: television "
                + "news by its captions; kind=video and kind=audio: films and recordings; kind=wiki: a Wikipedia article's revisions, and the article "
                + "as of a date (query = the title, lang = en, ja…); kind=heritage: museum and library items (Europeana, DPLA; needs a key).";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode().put("type", "object");
        ObjectNode props = p.putObject("properties");
        ObjectNode kind = props.putObject("kind").put("type", "string").put("description", "copy | history | site | texts | tv | video | audio | wiki | heritage");
        for (String k : List.of("copy", "history", "site", "texts", "tv", "video", "audio", "wiki", "heritage")) kind.withArray("enum").add(k);
        props.putObject("query").put("type", "string").put("description", "a page address (copy, history), a site or path (site), words (texts, tv, video, audio, heritage), or an article title (wiki)");
        props.putObject("at").put("type", "string").put("description", "a date, 2015-06-01 or 2015: the copy or revision nearest it (copy, wiki); optional");
        props.putObject("lang").put("type", "string").put("description", "wiki: the Wikipedia language, en unless given");
        props.putObject("limit").put("type", "integer").put("description", "how many rows, up to " + MOST + " (default 10)");
        p.putArray("required").add("kind").add("query");
        return p;
    }

    @Override public String execute(JsonNode args) {
        String kind = args.path("kind").asText("").strip().toLowerCase(), query = args.path("query").asText("").strip();
        String at = args.path("at").asText("").strip(), lang = args.path("lang").asText("en").strip();
        int limit = Math.max(1, Math.min(MOST, args.path("limit").asInt(10)));
        if (query.isEmpty()) return "ERROR: query is empty";
        if (calls.incrementAndGet() > MOST_CALLS) return "ERROR: this run has made its " + MOST_CALLS + " archive lookups; write with what was found.";
        StringBuilder sb = new StringBuilder();
        switch (kind) {
            case "copy" -> {
                String url = query.startsWith("http") ? query : "https://" + query;
                Archives.Copy c = Archives.nearest(url, at);
                if (c == null) return "no archived copy of " + url + " in the Wayback Machine or archive.today" + (at.isEmpty() ? "" : " near " + at) + " (or neither answered).";
                sb.append("archived copy of ").append(url).append(", saved on ").append(c.date()).append(" by the ").append(c.archive()).append(":\n  ").append(c.copyUrl())
                  .append("\nweb_fetch that address to read it; cite it with the date, as a copy of the page.");
            }
            case "history" -> {
                String url = query.startsWith("http") ? query : "https://" + query;
                List<Archives.Snapshot> s = Archives.history(url, limit);
                if (s.isEmpty()) return "no snapshots of " + url + " in the Wayback Machine (or its index did not answer; it takes several seconds).";
                sb.append("snapshots of ").append(url).append(" in the Wayback Machine, one per change of content, oldest first:\n");
                for (Archives.Snapshot x : s) sb.append("  ").append(x.date()).append("  status ").append(x.status()).append("  ").append(x.length()).append(" bytes  ").append(x.copyUrl(url)).append('\n');
                sb.append("web_fetch a copy's address to read the page as it was then.");
            }
            case "site" -> {
                List<Archives.SitePage> pages = Archives.site(query, limit);
                if (pages.isEmpty()) return "no pages under " + query + " in the Wayback Machine or Common Crawl (or neither answered).";
                sb.append("pages under ").append(query).append(" that the archives hold:\n");
                int shown = 0, refused = 0, listed = 0;
                for (Archives.SitePage pg : pages) {
                    if (policy.refused(pg.url())) { refused++; continue; }
                    if (policy.onSiteList(pg.url())) { listed++; continue; }
                    shown++;
                    sb.append("  ").append(pg.url()).append("  first seen ").append(new Archives.Copy("", pg.timestamp(), "", "").date()).append("  [").append(pg.archive()).append("]  copy: ").append(pg.copyUrl()).append('\n');
                }
                sb.append(WebSearchTool.leftOutLines(refused, listed));
                if (shown > 0) sb.append("web_fetch a copy's address to read it.");
            }
            case "texts", "tv", "video", "audio" -> {
                List<Archives.Item> items = Archives.search(query, kind, limit);
                List<Archives.Hit> hits = kind.equals("texts") ? Archives.fulltext(query, Math.min(limit, 8)) : List.of();
                if (items.isEmpty() && hits.isEmpty()) return "nothing in the Internet Archive's " + kind + " for: " + query + " (or it did not answer).";
                int n = 0;
                if (!items.isEmpty()) {
                    sb.append("Internet Archive ").append(kind).append(" for \"").append(query).append("\":\n");
                    for (Archives.Item it : items) {
                        sb.append(++n).append(". ").append(it.title()).append(it.creator().isEmpty() ? "" : " — " + it.creator()).append(it.date().isEmpty() ? "" : " (" + it.date() + ")").append('\n')
                          .append("   ").append(it.link()).append(kind.equals("texts") ? "   full text: " + it.textLink() : "").append('\n');
                        if (!it.description().isEmpty()) sb.append("   ").append(cut(it.description(), 200)).append('\n');
                    }
                }
                if (!hits.isEmpty()) {
                    sb.append("where the words occur in the scanned texts:\n");
                    for (Archives.Hit h : hits) {
                        sb.append(++n).append(". ").append(h.title()).append(h.creator().isEmpty() ? "" : " — " + h.creator()).append(h.date().isEmpty() ? "" : " (" + h.date() + ")").append(h.page() > 0 ? ", page " + h.page() : "").append('\n')
                          .append("   ").append(h.link()).append('\n');
                        if (!h.snippet().isEmpty()) sb.append("   …").append(cut(h.snippet(), 240)).append("…\n");
                    }
                }
                sb.append("web_fetch an item's full text to read it; cite the item and the page.");
            }
            case "wiki" -> {
                List<Archives.Revision> revs = Archives.revisions(lang, query, at, limit);
                if (revs.isEmpty()) return "no revisions of \"" + query + "\" on " + lang + ".wikipedia.org" + (at.isEmpty() ? "" : " before " + at) + " (or it did not answer).";
                sb.append("revisions of \"").append(query).append("\" on ").append(lang).append(".wikipedia.org").append(at.isEmpty() ? "" : " at or before " + at).append(", newest first:\n");
                for (Archives.Revision r : revs) sb.append("  ").append(r.timestamp().replace("T", " ").replace("Z", "")).append("  ").append(r.size()).append(" bytes  by ").append(r.user()).append(r.comment().isEmpty() ? "" : "  — " + cut(r.comment(), 100)).append("\n    ").append(Archives.revisionUrl(lang, query, r.revid())).append('\n');
                sb.append("web_fetch a revision's address to read the article as it was then.");
            }
            case "heritage" -> {
                List<String> noKey = new ArrayList<>();
                List<Archives.Heritage> items = Archives.heritage(query, limit, noKey);
                if (items.isEmpty()) return (noKey.size() == 2 ? "no heritage catalogue has a key on this library: " + String.join("; ", noKey) + ". " : "nothing in the heritage catalogues for: " + query + ". ")
                        + (noKey.isEmpty() ? "" : "Without a key: " + String.join("; ", noKey) + ".");
                sb.append("heritage items for \"").append(query).append("\":\n");
                int n = 0;
                for (Archives.Heritage h : items) sb.append(++n).append(". ").append(h.title()).append(h.date().isEmpty() ? "" : " (" + h.date() + ")").append(" — ").append(h.provider()).append(" [").append(h.source()).append("]\n   ").append(h.url()).append('\n');
                if (!noKey.isEmpty()) sb.append("not searched, no key: ").append(String.join("; ", noKey)).append('\n');
            }
            default -> { return "ERROR: kind must be one of copy, history, site, texts, tv, video, audio, wiki, heritage"; }
        }
        return Fence.wrap("ARCHIVE RESULTS", sb.toString().strip()) + "\n" + Fence.rule("ARCHIVE RESULTS");
    }

    static String cut(String s, int n) { s = s.replaceAll("\\s+", " ").strip(); return s.length() <= n ? s : s.substring(0, n - 1) + "…"; }
}
