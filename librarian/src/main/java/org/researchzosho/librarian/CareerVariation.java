package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A change for one search only, in the person's words: "without the pets field, and New Hampshire as well". The standing record, what they
 * decided, what they keep out, the employers they named, is the base and is not touched; the words are read against it, by the model, into
 * the topics as they stand for this run, the keep-outs for this run, and the employers named for it. The run's cut is made from these and
 * kept with the run's record, never as the standing cut.
 */
public final class CareerVariation {
    private CareerVariation() { }
    private static final ObjectMapper M = new ObjectMapper();

    /** The words, the topics as they stand for this run (the standing record with the words applied), the keep-outs for it, the employers it names, and a sentence on what changes. */
    public record Variation(String words, ObjectNode wants, List<String> keepOut, List<String> named, String said) { }

    /** The words read against the standing record. Words that change nothing give the record as it stands, and the sentence says so. */
    public static Variation read(LibraryStore store, Career.Seeker s, String words) throws IOException {
        ObjectNode wants = Career.wantsKept(store, s).deepCopy();
        List<String> keepOut = new ArrayList<>(CareerSearch.keepOut(store, s));
        if (words == null || words.isBlank()) return new Variation("", wants, keepOut, List.of(), "");
        StringBuilder standing = new StringBuilder();
        for (JsonNode d : wants.path("dimensions")) {
            String st = d.path("state").asText("open");
            if (!st.equals("open")) standing.append("- ").append(d.path("dimension").asText()).append(": ").append(st.equals("decided") ? "decided" : "a guess").append(", ").append(d.path("want").asText("")).append('\n');
        }
        List<String> topics = new ArrayList<>(); for (String d : Career.DIMENSIONS) topics.add(d + " (" + Career.label(d) + ")");
        ArrayNode msgs = M.createArrayNode();
        msgs.addObject().put("role", "user").put("content",
                "A person looking for work has a standing record of what they want, topic by topic, and a list of what they keep out. For ONE search only, they say what should "
                + "differ this time. Read their words against the record and give the topics as they stand for this search.\n\n"
                + "The topics: " + String.join("; ", topics) + ".\n\n"
                + "One entry for each topic the words change: {\"dimension\": \"the topic, as named before its brackets\", \"state\": \"decided\" or \"open\", \"want\": \"the want for this search, "
                + "one short sentence in the person's words\"}. A topic the words set aside (\"without the pets field\", \"any industry this time\") gets state open and an empty want. A topic the "
                + "words widen (\"and New Hampshire as well\" beside a decided place) gets state decided and a want that holds both the standing want and the addition. A topic the words "
                + "replace gets state decided and the new want. A topic the words do not touch gets no entry. Then what the words add to the keep-outs and what they lift from them, each in a "
                + "few of the person's words, and the employers the words name to look at. And one plain sentence, to the person, saying what changes for this search.\n\n"
                + "Answer with one JSON object and nothing else: {\"topics\": [...], \"keep_out_add\": [...], \"keep_out_drop\": [...], \"companies\": [...], \"said\": \"...\"}\n\n"
                + "THE STANDING RECORD\n" + (standing.isEmpty() ? "(nothing decided or guessed yet)\n" : standing)
                + "\nWHAT THEY KEEP OUT\n" + (keepOut.isEmpty() ? "(nothing)\n" : "- " + String.join("\n- ", keepOut) + "\n")
                + "\nFOR THIS SEARCH ONLY, in their words\n" + Fence.rule("their words") + "\n" + Fence.wrap("WORDS", words.strip()));
        String out;
        try { out = Career.DRIVE.get().classify(msgs, 1200); } catch (RuntimeException e) { out = null; }
        JsonNode j = CareerInterview.parseObject(out);
        if (!j.isObject() || j.isEmpty()) throw new IllegalStateException("The model did not read the change for this search. Check that the model server is running, then ask again.");
        List<String> changed = new ArrayList<>();
        for (JsonNode t : j.path("topics")) {
            String d = t.path("dimension").asText("").strip().toLowerCase(Locale.ROOT), st = t.path("state").asText("").strip().toLowerCase(Locale.ROOT), w = t.path("want").asText("").strip();
            if (!Career.DIMENSIONS.contains(d) || !(st.equals("decided") || st.equals("open") || st.equals("leaning"))) continue;
            ObjectNode e = Career.find(wants.path("dimensions"), d);
            if (e == null) { e = ((ArrayNode) wants.withArray("dimensions")).addObject(); e.put("dimension", d); }
            e.put("state", st.equals("open") ? "open" : st).put("want", st.equals("open") ? "" : w).put("by", "this search").put("from", "what you said for this search");
            changed.add(d);
        }
        for (JsonNode k : j.path("keep_out_add")) { String w = k.asText("").strip(); if (!w.isEmpty() && keepOut.stream().noneMatch(x -> x.equalsIgnoreCase(w))) keepOut.add(w); }
        for (JsonNode k : j.path("keep_out_drop")) { String w = k.asText("").strip(); keepOut.removeIf(x -> x.equalsIgnoreCase(w) || (!w.isEmpty() && x.toLowerCase(Locale.ROOT).contains(w.toLowerCase(Locale.ROOT)))); }
        List<String> named = new ArrayList<>();
        for (JsonNode c : j.path("companies")) { String n = c.asText("").strip(); if (!n.isEmpty()) named.add(n); }
        String said = j.path("said").asText("").strip();
        if (said.isEmpty()) said = changed.isEmpty() && named.isEmpty() ? "Your words change nothing the search uses; it runs on what you decided." : "For this search only: " + String.join(", ", changed) + (named.isEmpty() ? "" : "; employers named: " + Career.and(named)) + ".";
        return new Variation(words.strip(), wants, keepOut, named, said);
    }
}
