package org.researchzosho.librarian.profiles;

import org.researchzosho.librarian.Profile;
import org.researchzosho.librarian.Settings;
import org.researchzosho.librarian.Vocabulary;

import java.util.List;

/**
 * The shopping field (0.6.0): a question about what to buy. Its rules make the run a researcher for a purchase, not a ranker: the
 * candidates come from the market's shops through {@code product_search} and nothing else may be recommended; each is checked for its
 * specifications, its recall notices ({@code product_safety}), its repairability and what owners say; the table says fit against the
 * person's need, never a rank; the crowd's counts are cues, never evidence; sites whose terms forbid machine reading are pointed at;
 * prices carry the time they were seen; one market, the person's own, unless the question names another. It joins a run by the
 * question's words, as the other fields do; the tools are in every run.
 */
public final class ShoppingProfile implements Profile {

    @Override public String name() { return "shopping"; }

    @Override public String description() { return "what to buy: candidates for sale now in the person's own market, each checked for fit, specs, recalls, repair and owners' words; fit, never rank"; }

    @Override public List<Vocabulary.Term> predicates() {
        return List.of(
                new Vocabulary.Term("sells", "sells", List.of("lists", "offers", "stocks"), ""),
                new Vocabulary.Term("made-by", "is made by", List.of("is manufactured by", "is a product of"), ""),
                new Vocabulary.Term("recalled", "was recalled", List.of("is subject to a recall", "has a safety notice"), ""));
    }

    /** Words that mean the person is choosing something to buy. */
    static final List<String> WORDS = List.of(
            "buy", "buying", "purchase", "purchasing", "shopping", "cheapest", "worth buying", "which one should i", "what should i get", "recommend a", "recommend me",
            "looking for a", "looking to buy", "best value", "price of", "how much does", "where to buy", "where can i buy", "vs.", " versus ",
            "買う", "買い", "購入", "おすすめ", "お勧め", "比較", "価格", "値段", "安い", "どれが", "選び方", "買うなら", "コスパ");

    @Override public boolean applies(String question) {
        String q = Vocabulary.norm(question);
        for (String w : WORDS) if (q.contains(w)) return true;
        return false;
    }

    /** The needs-and-critique loop, which lives in the chat: the questions before a run, the critiques after it. */
    @Override public String chatNote() {
        return "A shopping question (\"which X should I buy\", \"looking for a Y under Z\", \"おすすめは\") is a conversation before it is a run. First restate the "
                + "need in one line as you understand it, then ask what you cannot infer, one question at a time and the one that would most narrow the "
                + "candidates first: what it must work with, the budget, the size or the space, the country if it is not the person's own (the market setting), "
                + "what they already have (library_owned op=find with the thing's words, and say when they own one). Two or three questions are usually enough; "
                + "stop asking when another answer would not change the shortlist. Then file the run with library_research and a question that carries every "
                + "constraint in words, and say it has started. When the report is back, serve a critique (\"quieter\", \"cheaper\", \"not that brand\", \"the "
                + "second one but bigger\") from the report itself first: library_get the report and re-cut its table by the new constraint, saying which rows "
                + "fall out and why; start a narrow follow-up run (\"Follow-up to <id>: …\", mode=depth) only when the critique needs something the run never "
                + "looked at, and say so. Never recommend a product that is not in a report's table; never say \"best\"; a star count or a badge is a cue, not a reason.";
    }

    @Override public String register() {
        return "This is a question about what to buy, and the run is a researcher for the purchase, not a ranker. The market is " + Settings.market()
                + " unless the question names another country; one market per run unless the person names several, and then every row says its market. "
                + "Only a product that product_search kind=shops returned in this run may be in the table: it is for sale now, with its price, shop and the time "
                + "the price was seen, which the row repeats. For each candidate: product_search kind=specs for the datasheet (brand and model, or the barcode "
                + "from kind=identity), product_safety for recall notices on file and whether the person already owns it, product_search kind=repair for the "
                + "repairability score where iFixit has one, product_search kind=owners and video_search and podcast_search for what owners say. For something used, vintage, handmade, out of print or sold at "
                + "auction, product_search kind=marketplaces: eBay listings behind the person's keys, and a typed-in search link for each marketplace the library "
                + "cannot read, which the table points at rather than quotes. my_shelf kind=profile says what the person bought lately and from where, so that a "
                + "thing adjacent to a recent purchase (the pail after the shovel) is noticed and a thing already bought is not proposed again. Judge fit "
                + "against the person's stated need and constraints, never against popularity: star counts, review counts, bestseller and choice badges are "
                + "cues to mention as cues, never evidence. A site whose terms forbid machine reading (Amazon, Consumer Reports, Wirecutter, Which?, Stiftung "
                + "Warentest, RTINGS, 価格.com) is pointed at by its address from a search result, never read; say \"X has a review\" with the link. Keep the "
                + "shortlist to six or fewer. Where nothing meets every constraint, say which constraint gives on each side rather than hiding one. A product "
                + "the maker lists a successor for is shown with the successor and its date. Your own memory of products is not a source.";
    }

    @Override public String planRules() {
        return "This is a shopping question. First restate the need and the constraints the person gave (what it must do, what it must work with, the budget, "
                + "the size, the country) and what was not said, as the criteria the table will judge by. Then one sub-question finds the candidates in the "
                + "market's shops (product_search kind=shops, with two or three different wordings of the thing), and one sub-question per candidate, six "
                + "at most, checks it: specs, recalls, repair, owners' words. One sub-question may look for the independent testers' reviews to point at "
                + "and for a successor model.";
    }

    @Override public String criticRules() {
        return "For a shopping question the evidence is enough when every candidate in the table came from product_search kind=shops in this run with a "
                + "price and a time, was asked of product_safety, and has at least one owner source or a datasheet; a candidate with none of those is dropped, "
                + "not kept with a guess. A gap worth another search names a constraint no candidate was checked against, or a market the person named "
                + "that was not searched.";
    }

    @Override public String writerRules() {
        return "Open with \"as of\" and today's date, the market, and the need as restated. Then the table, six rows at most: the product (its name as the shop "
                + "writes it, linked to the listing), what it does for the need, the price with its currency, shop and the time it was seen, what the datasheet "
                + "says that matters here, recall notices on file or \"none on file\", the repairability score where there is one, what owners say in a "
                + "sentence, and the one-line reason it fits or where it loses. No \"best\"; say which criterion each row wins or loses on. After the table: "
                + "the reviews to read, as links (the sites the run may not read), what could not be verified, and the successor models seen. Credit the "
                + "sources the shops ask for (Yahoo! JAPAN, Open Icecat) in a line at the end.";
    }
}
