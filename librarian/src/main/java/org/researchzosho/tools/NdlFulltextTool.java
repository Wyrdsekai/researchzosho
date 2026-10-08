package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.librarian.Fence;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code ndl_fulltext}: the National Diet Library's full-text search of digitised Japanese books (NDL Lab's 次世代デジタルライブラリー: about
 * 280,000 copyright-expired books and 80,000 classical works, OCR text with page coordinates). Terms read 2026-10-07: free without
 * application except for commercial and continuous use; the text and images carry the Public Domain Mark; say when the text was
 * processed; the service is experimental and may change without notice. The OCR text is machine-made and uncorrected.
 */
public final class NdlFulltextTool implements Tool {

    static final int MOST = 20, MOST_CALLS = 16;
    static volatile String BASE = "https://lab.ndl.go.jp/dl";
    private static final ObjectMapper M = new ObjectMapper();
    private final AtomicInteger calls = new AtomicInteger();

    @Override public String name() { return "ndl_fulltext"; }

    @Override public String description() {
        return "Full-text search of digitised Japanese books in the National Diet Library (copyright-expired works, to about 1970): local histories, "
                + "name directories, martial-arts manuals, school and company histories. keyword finds the books, with the pages where the words occur; "
                + "book_id with find returns the passages of one book around the words, with page numbers. The text is machine-read (OCR) and "
                + "uncorrected: quote it with that said, and cite the book, the page and the address.";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode().put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("keyword").put("type", "string").put("description", "the words, in Japanese as the books would write them (old forms where the period calls for them)");
        props.putObject("book_id").put("type", "string").put("description", "a book's id from an earlier result, to read inside it");
        props.putObject("find").put("type", "string").put("description", "with book_id: the words to find in that book");
        props.putObject("limit").put("type", "integer").put("description", "how many books or passages, up to " + MOST + " (default 10)");
        return p;
    }

    @Override public String execute(JsonNode args) {
        if (calls.incrementAndGet() > MOST_CALLS) return "ERROR: this run has made its " + MOST_CALLS + " NDL searches; write with what was found.";
        int limit = Math.max(1, Math.min(MOST, args.path("limit").asInt(10)));
        String bookId = args.path("book_id").asText("").strip(), find = args.path("find").asText("").strip(), keyword = args.path("keyword").asText("").strip();
        if (!bookId.isEmpty()) {
            if (find.isEmpty()) find = keyword;
            if (find.isEmpty()) return "ERROR: find is empty: the words to look for in book " + bookId;
            String body = Archives.text(BASE + "/api/page/search?f-book=" + Archives.enc(bookId) + "&q-contents=" + Archives.enc(find) + "&size=" + limit, Duration.ofSeconds(30), "the National Diet Library");
            if (body == null) return "ERROR: the NDL full-text service did not answer for book " + bookId + ". Try once more.";
            StringBuilder sb = new StringBuilder("passages of NDL book " + bookId + " holding \"" + find + "\" (OCR text, uncorrected; Public Domain Mark):\n");
            int n = 0;
            try {
                for (JsonNode pg : M.readTree(body).path("list")) {
                    String page = pg.path("page").asText(""), contents = pg.path("contents").asText("");
                    sb.append(++n).append(". page ").append(page).append("  ").append(BASE).append("/book/").append(bookId).append("?page=").append(page).append('\n');
                    for (String passage : around(contents, find, 160, 2)) sb.append("   …").append(passage).append("…\n");
                }
            } catch (Exception e) { return "ERROR: the NDL answer could not be read (" + e.getMessage() + ")"; }
            if (n == 0) sb.append("no page of this book holds the words.\n");
            return Fence.wrap("NDL TEXT", sb.toString().strip()) + "\n" + Fence.rule("NDL TEXT");
        }
        if (keyword.isEmpty()) return "ERROR: keyword is empty";
        String body = Archives.text(BASE + "/api/book/search?keyword=" + Archives.enc(keyword) + "&size=" + limit, Duration.ofSeconds(30), "the National Diet Library");
        if (body == null) return "ERROR: the NDL full-text service did not answer. This is not a search that found nothing: try once more.";
        StringBuilder sb = new StringBuilder("NDL digitised books whose text holds \"" + keyword + "\" (次世代デジタルライブラリー; copyright-expired works):\n");
        int n = 0;
        try {
            JsonNode root = M.readTree(body);
            for (JsonNode b : root.path("list")) {
                String id = b.path("id").asText("");
                sb.append(++n).append(". ").append(b.path("title").asText("")).append(b.path("volume").asText("").isEmpty() ? "" : " " + b.path("volume").asText(""))
                  .append(b.path("responsibility").asText("").isEmpty() ? "" : " — " + b.path("responsibility").asText("")).append(b.path("publisher").asText("").isEmpty() ? "" : ", " + b.path("publisher").asText(""))
                  .append(b.path("published").asText("").isEmpty() ? "" : " (" + b.path("published").asText("") + ")").append(b.path("ndc").asText("").isEmpty() ? "" : "  NDC " + b.path("ndc").asText("")).append('\n')
                  .append("   id ").append(id).append("  ").append(BASE).append("/book/").append(id).append('\n');
                int shown = 0;
                for (JsonNode h : b.path("highlights")) { if (shown++ >= 2) break; sb.append("   ").append(ArchiveSearchTool.cut(h.asText("").replaceAll("</?em>", "").replace("&#x2F;", "/"), 140)).append('\n'); }
            }
            if (n == 0) sb.append("nothing found").append(root.path("hit").isNumber() ? "" : "").append(".\n");
            else sb.append("ndl_fulltext with book_id and find reads inside one of them.");
        } catch (Exception e) { return "ERROR: the NDL answer could not be read (" + e.getMessage() + ")"; }
        return Fence.wrap("NDL RESULTS", sb.toString().strip()) + "\n" + Fence.rule("NDL RESULTS");
    }

    /** Up to {@code most} windows of {@code radius} characters around each occurrence of {@code words} in {@code text}. */
    static List<String> around(String text, String words, int radius, int most) {
        List<String> out = new ArrayList<>();
        if (text == null || words.isEmpty()) return out;
        int from = 0;
        while (out.size() < most) {
            int at = text.indexOf(words, from);
            if (at < 0) break;
            int a = Math.max(0, at - radius), b = Math.min(text.length(), at + words.length() + radius);
            out.add(text.substring(a, b).replaceAll("\\s+", " "));
            from = b;
        }
        return out;
    }
}
