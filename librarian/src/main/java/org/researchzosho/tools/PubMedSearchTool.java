package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code pubmed_search}: the pubmed place. PubMed by best match, each article with its journal, year, authors, kind and the start of its
 * abstract, at its PubMed Central address when the full text is free there, so that reading the address gives the whole article; or, with
 * {@code full_text}, PubMed Central's open articles searched inside their text, for a detail an abstract leaves out. Free; a key raises
 * NCBI's rate from three requests a second to ten.
 */
public final class PubMedSearchTool implements Tool {

    private volatile Fetch.Policy policy = Fetch.Policy.DEFAULT;

    public PubMedSearchTool policy(Fetch.Policy p) { this.policy = p == null ? Fetch.Policy.DEFAULT : p; return this; }

    @Override public String name() { return "pubmed_search"; }

    @Override public String description() {
        return "Search PubMed, the index of the medical and life-science literature, best match first: each article with its journal, year, "
                + "authors, kind (a trial, a review, a meta-analysis) and the start of its abstract. An article whose full text PubMed Central gives "
                + "out is given at its PubMed Central address, and reading that address gives the whole article with its sections. "
                + "full_text=true searches inside the open articles themselves, for a detail an abstract leaves out.";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("query").put("type", "string").put("description", "The words an article's title or abstract would use: a disease, a drug, a gene, a procedure.");
        props.putObject("limit").put("type", "integer").put("description", "How many articles (default 8, at most 20).");
        props.putObject("full_text").put("type", "boolean").put("description", "Search inside the open articles' full text instead of PubMed's titles and abstracts.");
        p.putArray("required").add("query");
        return p;
    }

    @Override public String execute(JsonNode args) {
        String query = args.path("query").asText("").strip();
        if (query.isBlank()) return "ERROR: empty query";
        int limit = Math.min(Math.max(args.path("limit").asInt(8), 1), 20);
        boolean full = args.path("full_text").asBoolean(false) || args.path("full_text").asText("").toLowerCase(Locale.ROOT).equals("true");
        PubMed.Found f = PubMed.search(query, limit, full);
        if (!f.answered()) return "ERROR: PubMed did not answer. This is not a search that found nothing: try once more, or search scholar instead.";
        SearchTally.count(PubMed.TALLY, 0);
        if (f.rows().isEmpty()) return "No articles in " + (full ? "PubMed Central's open full texts" : "PubMed") + " for: " + query + ". Try the words a title or an abstract would use, or fewer of them.";
        List<PlaceRows.Hit> hits = new ArrayList<>();
        for (PubMed.Row r : f.rows()) hits.add(hit(r));
        String heading = full ? " (PubMed Central, inside the open articles' full text: " + f.rows().size() + " of " + f.count() + ", best match first)"
                : " (PubMed: " + f.rows().size() + " of " + f.count() + ", best match first)";
        return PlaceRows.render(query, heading, hits, limit, policy);
    }

    static PlaceRows.Hit hit(PubMed.Row r) {
        List<String> what = new ArrayList<>();
        String src = (r.journal() + (r.year().isEmpty() ? "" : ", " + r.year())).strip();
        if (!src.isEmpty()) what.add(src);
        if (!r.authors().isEmpty()) what.add(PlaceRows.authors(r.authors()));
        if (!r.types().isEmpty()) what.add(String.join(", ", r.types()));
        String publisher = r.doi().isEmpty() ? "" : "; the article is at https://doi.org/" + r.doi();
        what.add(r.pmcid().isEmpty() ? "the abstract only" + publisher
                : Boolean.TRUE.equals(r.open()) ? "the full text can be read at this address"
                : Boolean.FALSE.equals(r.open()) ? "the abstract only; a person can read the full text at " + PubMed.pmcAddress(r.pmcid())
                : "in PubMed Central at this address");
        List<String> lines = new ArrayList<>();
        lines.add(String.join(" · ", what));
        if (!r.abstractText().isBlank()) lines.add(PlaceRows.cut(r.abstractText(), 320));
        return new PlaceRows.Hit(r.title(), r.address(), lines);
    }
}
