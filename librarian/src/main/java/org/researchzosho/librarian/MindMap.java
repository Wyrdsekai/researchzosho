package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The `/research` mode's live concept tree — Co-STORM's "shared conceptual space between the
 * user and the system" (docs/REF_SCIENCE_HARNESSES.md), sized for v1. As the conversation
 * retrieves, information lands under concepts; the session's product (an investigation the
 * acquisitions desk receives) is generated FROM the tree, not from the transcript.
 *
 * <p>Division of labor, as everywhere in the librarian: the MODEL proposes insertions (which
 * concept a new piece of information belongs under — a judgment); the MACHINE applies them,
 * keeps the tree tidy (merge single-child chains, drop empty leaves — Co-STORM's bottom-up
 * cleaning), enforces bounds, and renders. The model never mutates the tree directly.
 */
public final class MindMap {

    /** Bounds — a tree that grows without limit quietly rebuilds "accumulate". */
    static final int MAX_NODES = 60;
    static final int MAX_NOTES_PER_NODE = 8;
    static final int MAX_NOTE_LEN = 300;

    static final class Node {
        final String concept;
        final List<String> notes = new ArrayList<>();
        final List<Node> children = new ArrayList<>();
        Node(String concept) { this.concept = concept; }
    }

    private final Node root;
    private int nodeCount = 1;

    public MindMap(String topic) {
        this.root = new Node(topic.strip());
    }

    public String topic() { return root.concept; }
    public int nodes() { return nodeCount; }

    /**
     * Apply model-proposed insertions: [{"parent": "concept or the topic itself", "concept":
     * "where this belongs (created if new)", "note": "the information"}]. Unknown parents attach
     * under the root rather than erroring — a misfiled note is findable; a dropped one is gone.
     * Returns how many notes landed.
     */
    public int apply(JsonNode insertions) {
        if (insertions == null || !insertions.isArray()) return 0;
        int applied = 0;
        for (JsonNode ins : insertions) {
            String concept = ins.path("concept").asText("").strip();
            String note = ins.path("note").asText("").strip();
            if (concept.isEmpty() || note.isEmpty()) continue;
            if (note.length() > MAX_NOTE_LEN) note = note.substring(0, MAX_NOTE_LEN - 1) + "…";
            Node parent = find(root, ins.path("parent").asText("").strip());
            if (parent == null) parent = root;
            Node target = childOf(parent, concept);
            if (target == null) {
                if (nodeCount >= MAX_NODES) target = parent;  // full tree: note lands, no new node
                else {
                    target = new Node(concept);
                    parent.children.add(target);
                    nodeCount++;
                }
            }
            if (target.notes.size() < MAX_NOTES_PER_NODE && !target.notes.contains(note)) {
                target.notes.add(note);
                applied++;
            }
        }
        tidy(root);
        return applied;
    }

    /** Co-STORM's bottom-up cleaning, mechanical half: drop empty leaves, merge single-child chains. */
    private void tidy(Node n) {
        n.children.removeIf(c -> {
            tidy(c);
            boolean empty = c.notes.isEmpty() && c.children.isEmpty();
            if (empty) nodeCount--;
            return empty;
        });
        // merge a child that is a bare pass-through (no notes, exactly one child) into this level
        for (int i = 0; i < n.children.size(); i++) {
            Node c = n.children.get(i);
            if (c.notes.isEmpty() && c.children.size() == 1) {
                n.children.set(i, c.children.get(0));
                nodeCount--;
            }
        }
    }

    private static Node find(Node n, String concept) {
        if (concept.isEmpty()) return null;
        if (n.concept.toLowerCase(Locale.ROOT).equals(concept.toLowerCase(Locale.ROOT))) return n;
        for (Node c : n.children) {
            Node hit = find(c, concept);
            if (hit != null) return hit;
        }
        return null;
    }

    private static Node childOf(Node parent, String concept) {
        for (Node c : parent.children) {
            if (c.concept.equalsIgnoreCase(concept)) return c;
        }
        return null;
    }

    /** The outline pushed into each research-mode turn — what we hold, organized, at a glance. */
    public String render() {
        StringBuilder sb = new StringBuilder();
        render(root, 0, sb);
        return sb.toString();
    }

    private static void render(Node n, int depth, StringBuilder sb) {
        sb.append("  ".repeat(depth)).append(depth == 0 ? "◆ " : "- ").append(n.concept).append('\n');
        for (String note : n.notes) {
            sb.append("  ".repeat(depth + 1)).append("· ").append(note).append('\n');
        }
        for (Node c : n.children) render(c, depth + 1, sb);
    }

    /** The session product: an investigation body generated FROM the tree, not the transcript. */
    public String toInvestigationBody() {
        StringBuilder sb = new StringBuilder("## Question\n\n").append(root.concept).append("\n\n")
                .append("## What the conversation established (from the mind map)\n\n");
        for (Node c : root.children) section(c, 3, sb);
        for (String note : root.notes) sb.append("- ").append(note).append('\n');
        List<String> urls = Acquisitions.urls(render());
        if (!urls.isEmpty()) {
            sb.append("\n## Sources cited\n\n");
            for (String u : urls) sb.append("- ").append(u).append('\n');
        }
        return sb.toString();
    }

    private static void section(Node n, int level, StringBuilder sb) {
        sb.append("#".repeat(Math.min(level, 6))).append(' ').append(n.concept).append("\n\n");
        for (String note : n.notes) sb.append("- ").append(note).append('\n');
        if (!n.notes.isEmpty()) sb.append('\n');
        for (Node c : n.children) section(c, level + 1, sb);
    }
}
