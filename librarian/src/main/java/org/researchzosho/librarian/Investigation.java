package org.researchzosho.librarian;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One research run, one file: the question, what it produced, and what stayed open.
 *
 * <p>Deliberately lighter than {@link Finding} — an investigation is a RECORD of work, not a
 * claim, so it has no claim type, no volatility, no review cycle of its own. Its findings carry
 * those. The body holds the sub-questions and the synthesis as prose; {@code findings} links
 * the atomic claims this run submitted; {@code open} is what the run could not settle, each
 * line also appended to the frontier by the acquisitions crew.
 */
public record Investigation(
        String id,                // I-<n>-<slug>, equals the filename stem
        String title,             // the question, compressed
        Finding.State state,      // draft | accepted (investigations never dispute/supersede)
        String writer,
        String recordedAt,
        List<String> findings,    // ids of findings this run produced
        List<String> open,        // open questions left behind (frontier-bound)
        String body) {

    private static final Set<String> KNOWN_SCALARS =
            Set.of("schema", "id", "title", "state", "writer", "recorded_at");
    private static final Set<String> KNOWN_LISTS = Set.of("findings", "open");

    public String format() {
        StringBuilder sb = new StringBuilder("---\n");
        sb.append("schema: 1\n");
        sb.append("id: ").append(id).append('\n');
        sb.append("title: ").append(title).append('\n');
        sb.append("state: ").append(state).append('\n');
        sb.append("writer: ").append(writer).append('\n');
        sb.append("recorded_at: ").append(recordedAt).append('\n');
        sb.append("findings: [").append(String.join(", ", findings)).append("]\n");
        if (!open.isEmpty()) {   // block form: an open question with a comma in it must come back as one question
            sb.append("open:\n");
            for (String o : open) sb.append("- ").append(o.strip().replaceAll("\\s+", " ")).append('\n');
        }
        sb.append("---\n").append(body);
        return sb.toString();
    }

    public static Investigation parse(String text) {
        Frontmatter.Head h = Frontmatter.parse(text);
        for (String k : h.scalars().keySet()) {
            if (!KNOWN_SCALARS.contains(k)) {
                throw new IllegalArgumentException("unknown frontmatter key: " + k);
            }
        }
        for (String k : h.lists().keySet()) {
            if (!KNOWN_LISTS.contains(k)) {
                throw new IllegalArgumentException("unknown frontmatter list: " + k);
            }
        }
        if (!"1".equals(h.scalars().get("schema"))) {
            throw new IllegalArgumentException("unsupported schema: " + h.scalars().get("schema"));
        }
        String id = h.scalars().get("id");
        if (id == null || !id.matches("I-\\d+-[a-z0-9-]+")) {
            throw new IllegalArgumentException("investigation id is not I-<n>-<slug>: " + id);
        }
        String title = h.scalars().get("title");
        String writer = h.scalars().get("writer");
        String at = h.scalars().get("recorded_at");
        if (title == null || writer == null || at == null) {
            throw new IllegalArgumentException("investigation missing title/writer/recorded_at");
        }
        Finding.State state = Finding.State.valueOf(h.scalars().getOrDefault("state", "draft"));
        if (state != Finding.State.draft && state != Finding.State.accepted) {
            throw new IllegalArgumentException("investigation state must be draft or accepted: " + state);
        }
        return new Investigation(id, title, state, writer, at,
                new ArrayList<>(h.lists().getOrDefault("findings", List.of())),
                new ArrayList<>(h.lists().getOrDefault("open", List.of())),
                h.body());
    }
}
