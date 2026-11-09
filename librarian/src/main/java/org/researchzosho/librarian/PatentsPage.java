package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.records.Ops;

import java.util.Map;

import static org.researchzosho.librarian.Pages.t;

/** The Patents page, {@code /patents}: the pool of a classification's patents, the reading of its abstracts, and the claims from the office's own full text. */
public final class PatentsPage {
    private PatentsPage() { }

    public static String esc(String s) { return Pages.esc(s); }

    public static String body(LibraryStore store, boolean writer) {
        StringBuilder b = new StringBuilder();
        b.append("<p class=\"k\">").append(t("Whether something like an invention was already filed or granted: the library takes every patent publication of a classification in a span of years from the European Patent Office's own records, never a search engine's first page, reads their abstracts for the invention's features, and then reads the claims of those that pass.")).append("</p>");
        if (!Ops.keyed()) b.append("<p class=\"notice\">").append(esc(Ops.missing())).append("</p>");
        b.append("<h2 id=\"pool\">").append(t("The pool: every patent of a class")).append("</h2><p>").append(t("Give the classification symbols the invention sits in, each a group with its slash, the years, and the invention's features, one on a line, each with the words and synonyms that would name it in a title or an abstract, separated by commas, in every language you can give. The library says how many publications the office counts, keeps one line a patent family, and lists the pool by how many features each names, the rarer feature first, then the older, with no citation count or other popularity in the order. A class of a thousand publications takes two to five minutes.")).append("</p>");
        if (writer) b.append("<form method=\"post\" action=\"/patents\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"pool\"><input name=\"classes\" size=\"28\" placeholder=\"A47G19/22\" aria-label=\"").append(esc(t("the classification symbols, comma-separated"))).append("\"> <input name=\"from_year\" size=\"5\" placeholder=\"2005\" aria-label=\"").append(esc(t("the first year"))).append("\"> <input name=\"to_year\" size=\"5\" placeholder=\"2015\" aria-label=\"").append(esc(t("the last year")))
                .append("\"><textarea name=\"features\" rows=\"3\" placeholder=\"").append(esc(t("lid, cover, cap\nstraw, drinking tube\nchanges colour with heat, thermochromic"))).append("\" aria-label=\"").append(esc(t("the features, one on a line, each with its words"))).append("\"></textarea><input name=\"exclude\" size=\"40\" placeholder=\"").append(esc(t("numbers already rejected"))).append("\" aria-label=\"").append(esc(t("publication numbers already rejected, comma-separated"))).append("\"> <button>").append(t("Enumerate the class")).append("</button></form>");
        b.append("<h2 id=\"abstracts\">").append(t("Read the abstracts")).append("</h2><p>").append(t("Give the key the pool's answer named, and the library's model reads the abstract of every family in the pool, best fit first, twenty to a call, and says of each feature yes, maybe or no. It takes about ten seconds for twenty abstracts on a local model; a call reads four hundred families at most and says how many are left, and nothing is read twice.")).append("</p>");
        if (writer) b.append("<form method=\"post\" action=\"/patents\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"abstracts\"><input name=\"from\" size=\"28\" placeholder=\"").append(esc(t("the patents pool's key"))).append("\" aria-label=\"").append(esc(t("the key of the pool of patents"))).append("\"> <button>").append(t("Read them")).append("</button></form>");
        b.append("<h2 id=\"claims\">").append(t("Read the claims")).append("</h2><p>").append(t("Give the pool's key, and the library reads the claims of the families that pass, best first, from the patent office's own full text, and quotes for each feature the claim that comes closest; or give patent numbers, and it reads each one's record and claims. Where the office holds no text for a publication, as for the United States, China, Japan and Korea, it reads the member of the same family whose text it does hold, or else Google Patents' copy of the office's record at the publication's own address there, and says which. It takes about twenty seconds a family; a call reads twenty at most.")).append("</p>");
        if (writer) b.append("<form method=\"post\" action=\"/patents\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"claims\"><input name=\"from\" size=\"28\" placeholder=\"").append(esc(t("the patents pool's key"))).append("\" aria-label=\"").append(esc(t("the key of the pool of patents"))).append("\"> <input name=\"publications\" size=\"34\" placeholder=\"EP1000000A1\" aria-label=\"").append(esc(t("or patent numbers, comma-separated"))).append("\"> <button>").append(t("Read the claims")).append("</button></form>");
        return b.toString();
    }

    /** A form's fields as the protocol's arguments. */
    public static ObjectNode args(String op, Map<String, String> form, Patrons.Patron patron, String runtime) {
        ObjectNode a = new ObjectMapper().createObjectNode().put("op", op);
        for (String k : new String[]{"classes", "from", "exclude", "publications"}) if (!form.getOrDefault(k, "").isBlank()) a.put(k, form.get(k).strip());
        for (String k : new String[]{"from_year", "to_year", "limit", "page"}) if (form.getOrDefault(k, "").strip().matches("\\d+")) a.put(k, Integer.parseInt(form.get(k).strip()));
        if (!form.getOrDefault("features", "").isBlank()) { ArrayNode f = a.putArray("features"); for (String line : form.get("features").split("\\R|;")) if (!line.isBlank()) f.add(line.strip()); }
        a.putObject("patron").put("did", patron.did()).put("name", patron.name()).put("runtime", runtime);
        return a;
    }
}
