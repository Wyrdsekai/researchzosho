package org.researchzosho.librarian;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The librarian's frontmatter grammar — deliberately SMALL and parsed STRICTLY.
 *
 * <p>Library entries are markdown files whose head is fenced by {@code ---} lines. The grammar
 * is a subset of YAML chosen so a person can read and edit it in any editor, while the parser
 * stays ~100 dependency-free lines and FAILS CLOSED (the GPD schema rule: version drift or an
 * unknown shape is an error, never a guess — a knowledge store that silently accepts malformed
 * entries accumulates exactly the garbage the librarian exists to keep out):
 *
 * <pre>
 *   key: value                          scalar
 *   key: [a, b, c]                      inline list (no nesting)
 *   source: locator | edition | why     repeatable line; pipes separate the three parts
 *   review: k=v k=v ...                 space-separated tokens, values may not contain spaces
 * </pre>
 *
 * <p>No block lists, no nested maps, no multiline scalars. Anything else in the fence is an
 * error naming the line — the writer fixes the entry, the store never "repairs" it.
 */
final class Frontmatter {

    /** Parsed head: scalars, inline lists, and the repeatable {@code source:} lines in order. */
    record Head(Map<String, String> scalars, Map<String, List<String>> lists,
                List<String> sourceLines, List<String> noteLines, String body) {
        Head(Map<String, String> scalars, Map<String, List<String>> lists, List<String> sourceLines, String body) {
            this(scalars, lists, sourceLines, List.of(), body);
        }
    }

    private Frontmatter() { }

    /** Parse a full file. Throws {@link IllegalArgumentException} with the offending line. */
    static Head parse(String text) {
        if (text == null || !text.startsWith("---\n")) {
            throw new IllegalArgumentException("entry does not start with a '---' frontmatter fence");
        }
        int end = text.indexOf("\n---", 3);
        if (end < 0) throw new IllegalArgumentException("frontmatter fence is never closed");
        String head = text.substring(4, end + 1);
        int bodyStart = text.indexOf('\n', end + 1);
        String body = bodyStart < 0 ? "" : text.substring(bodyStart + 1);

        Map<String, String> scalars = new LinkedHashMap<>();
        Map<String, List<String>> lists = new LinkedHashMap<>();
        List<String> sources = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        String pendingList = null;   // a `key:` line with nothing after it opens a block list of `- item` lines
        for (String line : head.split("\n")) {
            if (line.isBlank()) continue;
            if (pendingList != null && line.startsWith("- ")) {
                lists.get(pendingList).add(line.substring(2).strip());
                continue;
            }
            pendingList = null;
            int colon = line.indexOf(':');
            if (colon <= 0) throw new IllegalArgumentException("frontmatter line has no key: " + line);
            String key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            if (!key.matches("[a-z_]+")) {
                throw new IllegalArgumentException("frontmatter key is not lowercase_snake: " + key);
            }
            if (value.isEmpty() && !key.equals("source") && !key.equals("note")) {
                // a block list: items follow, one per `- ` line — the form that survives a comma in an item
                if (lists.put(key, new ArrayList<>()) != null || scalars.containsKey(key)) {
                    throw new IllegalArgumentException("duplicate frontmatter key: " + key);
                }
                pendingList = key;
            } else if (key.equals("source")) {
                sources.add(value);
            } else if (key.equals("note")) {
                notes.add(value);   // meta-facts: repeated lines, like sources
            } else if (value.startsWith("[")) {
                if (!value.endsWith("]")) {
                    throw new IllegalArgumentException("inline list is never closed: " + line);
                }
                List<String> items = new ArrayList<>();
                String inner = value.substring(1, value.length() - 1).strip();
                if (!inner.isEmpty()) {
                    for (String item : inner.split(",")) {
                        String s = item.strip();
                        if (s.isEmpty()) throw new IllegalArgumentException("empty list item: " + line);
                        items.add(s);
                    }
                }
                if (lists.put(key, items) != null || scalars.containsKey(key)) {
                    throw new IllegalArgumentException("duplicate frontmatter key: " + key);
                }
            } else {
                if (scalars.put(key, value) != null || lists.containsKey(key)) {
                    throw new IllegalArgumentException("duplicate frontmatter key: " + key);
                }
            }
        }
        return new Head(scalars, lists, sources, notes, body);
    }

    /** Parse a {@code review:}-style token line: {@code k=v k=v ...}. */
    static Map<String, String> tokens(String line) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String tok : line.strip().split("\\s+")) {
            if (tok.isEmpty()) continue;
            int eq = tok.indexOf('=');
            if (eq <= 0) throw new IllegalArgumentException("token has no '=': " + tok);
            out.put(tok.substring(0, eq), tok.substring(eq + 1));
        }
        return out;
    }
}
