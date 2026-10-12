package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.librarian.LibraryStore;
import org.researchzosho.librarian.Owned;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code product_safety}: the recall notices on file that name a product (by barcode, by brand and model, or by the maker and the words),
 * from the open feeds the housekeeping keeps, and whether the person already owns something like it. Reads the library only; the
 * feeds are fetched by the recall watch, never here.
 */
public final class ProductSafetyTool implements Tool {

    private final LibraryStore store;

    public ProductSafetyTool(LibraryStore store) { this.store = store; }

    @Override public String name() { return "product_safety"; }

    @Override public String description() {
        return "Recall notices about a product, from the open safety feeds the library keeps (US CPSC and FDA, EU Safety Gate, UK OPSS, the OECD portal), "
                + "by barcode, by brand and model number, or by the maker and the words of the name; and whether the person already owns something that reads like "
                + "it, from their ledger. Ask it for every candidate before it goes in the table; say \"no notice on file\" when there is none, never \"safe\".";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode().put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("name").put("type", "string").put("description", "the product's name as a shop writes it");
        props.putObject("brand").put("type", "string").put("description", "the maker");
        props.putObject("model").put("type", "string").put("description", "the model number");
        props.putObject("barcode").put("type", "string").put("description", "the barcode when known");
        p.putArray("required").add("name");
        return p;
    }

    @Override public String execute(JsonNode args) throws Exception {
        String name = args.path("name").asText("").strip(), brand = args.path("brand").asText("").strip().toLowerCase(Locale.ROOT), model = args.path("model").asText("").strip().toUpperCase(Locale.ROOT), barcode = Recalls.code(args.path("barcode").asText(""));
        if (name.isEmpty()) return "ERROR: name is needed.";
        List<Recalls.Notice> all = Recalls.notices();
        StringBuilder b = new StringBuilder();
        if (all.isEmpty()) b.append("No recall feed is on file yet (the housekeeping fetches them nightly; researchzosho owned recalls check fetches them now). ");
        List<String> hits = new ArrayList<>();
        String nameBrand = brand.isEmpty() ? firstWord(name) : brand;
        Set<String> models = model.isEmpty() ? Recalls.modelsOf(name) : Set.of(model);
        Set<String> words = Recalls.wordsOf(name);
        for (Recalls.Notice n : all) {
            String hay = (n.title() + " " + n.brand() + " " + n.model() + " " + n.text()).toLowerCase(Locale.ROOT);
            String why = null;
            if (!barcode.isEmpty()) for (String c : n.barcodes()) if (Recalls.code(c).equals(barcode)) { why = "the barcode " + barcode; break; }
            if (why == null && !nameBrand.isEmpty() && nameBrand.length() >= 3 && Recalls.wholeToken(hay, nameBrand)) {
                for (String mo : models) if (Recalls.wholeToken(hay, mo.toLowerCase(Locale.ROOT))) { why = "the brand " + nameBrand + " and the model " + mo; break; }
                if (why == null && n.brand().toLowerCase(Locale.ROOT).contains(nameBrand)) {
                    int shared = 0; for (String w : words) if (!w.equals(nameBrand) && hay.contains(w)) shared++;
                    if (shared >= 3) why = "the maker " + nameBrand + " named in the notice and " + shared + " words of the name";
                }
            }
            if (why != null) hits.add("- " + Shops.clean(n.title()) + " (" + n.feed().toUpperCase(Locale.ROOT) + ", " + n.date() + (n.country().isEmpty() ? "" : ", " + n.country()) + "), matched by " + why + " — " + n.url());
            if (hits.size() >= 10) break;
        }
        if (hits.isEmpty()) b.append("No recall notice on file names \"").append(Shops.clean(name)).append("\" (").append(all.size()).append(" notices on file). That is \"no notice on file\", not \"safe\".");
        else { b.append(hits.size()).append(" recall notice(s) on file may concern \"").append(Shops.clean(name)).append("\":\n").append(String.join("\n", hits)); }
        List<Owned.Line> owned = Owned.alreadyOwned(store, name);
        if (!owned.isEmpty()) {
            b.append("\nThe person already owns something that reads like it:");
            int n = 0; for (Owned.Line l : owned) { if (n++ >= 3) break; b.append("\n- ").append(Shops.clean(l.title())).append(" — ").append(l.shop()).append(", ").append(l.date()); }
            b.append("\nSay so in the table: a thing already owned is not recommended again.");
        }
        return b.toString();
    }

    static String firstWord(String name) {
        String w = name.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")[0];
        return Recalls.NOT_BRANDS.contains(w) || w.length() < 3 ? "" : w;
    }
}
