package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Source requests: what the runner could not read — a paywall, a cookie wall, a bot wall, a login —
 * and asks the person for. A wall is not a source, and it is not the end either: the person may hold
 * the paper, have the institutional access, or know the open version. Each request is one line in
 * {@code catalog/requests.md}; the investigation lists the ones its run hit; {@code researchzosho add
 * <file> --for <url>} supplies the document, which becomes the capture BEHIND that url — so the
 * references, the cite-check and the inventory resolve to it from then on — and a {@code supplied}
 * change goes on the feed.
 */
public final class Requests {

    public record Request(String date, String state, String locator, String reason, String context) { }

    private Requests() { }

    static Path file(LibraryStore store) { return store.root().resolve("catalog").resolve("requests.md"); }

    /** Record that {@code locator} could not be read. Idempotent per locator while the request is open. */
    public static synchronized void note(LibraryStore store, String locator, String reason, String context) throws IOException {
        for (Request r : read(store)) if (r.state().equals("open") && r.locator().equals(locator)) return;
        Files.createDirectories(file(store).getParent());
        if (!Files.exists(file(store))) {
            Files.writeString(file(store), "# Source requests — what the runner could not read; supply with `researchzosho add <file> --for <url>`\n\n", StandardCharsets.UTF_8);
        }
        Files.writeString(file(store), "- " + LocalDate.now() + " [open] " + locator + " — " + reason.replaceAll("\\s+", " ") + " — for: " + Acquisitions.compress(context == null ? "" : context, 120) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        store.circulate("source-request", reason + " :: " + locator);
    }

    public static List<Request> read(LibraryStore store) throws IOException {
        List<Request> out = new ArrayList<>();
        if (!Files.exists(file(store))) return out;
        for (String line : Files.readAllLines(file(store), StandardCharsets.UTF_8)) {
            if (!line.startsWith("- ")) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^- (\\S+) \\[(\\w+)\\] (\\S+) — (.*?)(?: — for: (.*))?$").matcher(line);
            if (m.matches()) out.add(new Request(m.group(1), m.group(2), m.group(3), m.group(4), m.group(5) == null ? "" : m.group(5)));
        }
        return out;
    }

    public static List<Request> open(LibraryStore store) throws IOException {
        List<Request> out = new ArrayList<>();
        for (Request r : read(store)) if (r.state().equals("open")) out.add(r);
        return out;
    }

    /**
     * The person supplies the document for {@code locator}: its text is captured under that url (so
     * {@link RawCapture#find} answers with it), the request is marked supplied, and the feed says so.
     */
    public static synchronized Path supply(LibraryStore store, String locator, String text, String title) throws IOException {
        Path p = RawCapture.capture(store, locator, text, title, "person-supplied", "");
        if (p == null) throw new IOException("the document could not be captured");
        if (Files.exists(file(store))) {
            List<String> lines = new ArrayList<>(Files.readAllLines(file(store), StandardCharsets.UTF_8));
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith("- ") && lines.get(i).contains(" [open] " + locator + " ")) lines.set(i, lines.get(i).replace(" [open] ", " [supplied " + LocalDate.now() + "] "));
            }
            Files.writeString(file(store), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        }
        Changes.append(store, "source", locator, "supplied", "the person supplied the document behind this locator");
        store.circulate("source-supplied", locator);
        return p;
    }
}
