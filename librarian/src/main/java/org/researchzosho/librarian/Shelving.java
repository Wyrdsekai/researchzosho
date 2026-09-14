package org.researchzosho.librarian;

import org.researchzosho.tools.DocText;
import org.researchzosho.tools.Fetch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

/**
 * One locator onto the shelves, for the launching points that carry many: fetched, converted, kept; a
 * wall or a failure becomes a source request the person can answer with {@code add <file> --for <url>}.
 */
public final class Shelving {

    /** What became of one locator. {@code raw} is null when nothing was shelved; {@code requested} when a request was filed. */
    public record Got(String locator, Path raw, String title, String kind, String problem, boolean requested) {
        public boolean shelved() { return raw != null; }
    }

    private Shelving() { }

    /** A DOI as the url the world resolves it at. */
    public static String doiUrl(String doi) { return "https://doi.org/" + doi.strip(); }

    /** Fetch, convert, keep. {@code context} says what the request is for when one has to be filed. */
    public static Got fetch(LibraryStore store, String url, String collection, String context) {
        String u = url.strip();
        try {
            // already on the shelves: say so, fetch nothing
            Path have = RawCapture.find(store, u);
            if (have != null) { String[] r = RawCapture.read(have); if (!RawCapture.looksBinary(r[2]) && Fetch.wall(r[1], r[2]) == null) return new Got(u, have, r[1], "held", "", false); }
            Fetch.Result resp = Fetch.get(u, Duration.ofSeconds(60));
            if (resp.status() >= 400) return request(store, u, "HTTP " + resp.status(), context);
            DocText.Doc doc = DocText.convert(resp.body(), u);
            if (doc.text().isBlank()) return request(store, u, "no text could be read (" + doc.kind() + ")", context);
            String title = doc.title();
            try { Citations.Meta meta = Citations.resolve(store, resp.url(), Citations.LIVE); if (meta != null && !meta.title().isEmpty()) title = meta.title(); } catch (Exception ignored) { }
            String wall = Fetch.wall(title, doc.text());
            if (wall != null) return request(store, u, "a wall: " + wall, context);
            String published = "";
            try { published = org.researchzosho.tools.WebFetchTool.publishedDate(new String(resp.body(), 0, Math.min(resp.body().length, 200_000), StandardCharsets.UTF_8)); } catch (Exception ignored) { }
            Path raw = RawCapture.capture(store, resp.url(), doc.text(), title, "researchzosho-" + (collection == null || collection.isBlank() ? "add" : collection), collection, published);
            if (raw == null) return new Got(u, null, title, doc.kind(), "not shelved (no library, or the capture was refused)", false);
            return new Got(u, raw, title, doc.kind(), "", false);
        } catch (Exception e) {
            return request(store, u, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), context);
        }
    }

    private static Got request(LibraryStore store, String url, String reason, String context) {
        boolean filed = false;
        try { Requests.note(store, url, reason, context); filed = true; } catch (IOException ignored) { }
        return new Got(url, null, "", "", reason, filed);
    }
}
