package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.CraftsProfile;
import org.researchzosho.tools.ContentPolicy;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The kinds of doing and the crafts field (AFTER_0_6 §1): the shipped list loads with the sources it was made from, a question's kinds
 * are found by name in English and Japanese, the field applies on the words of doing and suggests itself on a kind's name, and a run of it
 * is told what the list knows.
 */
class CraftsTest {

    @Test
    void theListLoadsFromEverySource() {
        List<Crafts.Kind> all = Crafts.all();
        assertTrue(all.size() > 3500, "about four thousand kinds: " + all.size());
        assertTrue(all.stream().anyMatch(k -> k.sources().stream().anyMatch(s -> s.path("taxonomy").asText().equals("wikidata"))));
        assertTrue(all.stream().anyMatch(k -> k.sources().stream().anyMatch(s -> s.path("taxonomy").asText().equals("wikipedia"))));
        Crafts.Kind meti = Crafts.byId("meti-0601");
        assertNotNull(meti); assertEquals("二風谷イタ", meti.ja()); assertEquals("北海道", meti.region()); assertTrue(meti.designation().contains("伝統的工芸品"), meti.designation());
        assertEquals(List.of("japanese-woodwork-and-bamboo-work"), meti.parents());
        assertEquals("Japanese woodwork and bamboo work (木工品・竹工品)", Crafts.byId("japanese-woodwork-and-bamboo-work").name());
        Crafts.Kind gold = Crafts.byId("gold-beating");
        assertNotNull(gold); assertEquals("Extinct in the UK", gold.risk()); assertEquals("United Kingdom", gold.region());
        assertTrue(Crafts.describe(gold).contains("on the Heritage Crafts Red List: extinct in the uk (United Kingdom)") && Crafts.describe(gold).contains("heritagecrafts.org.uk/craft/gold-beating/"), Crafts.describe(gold));
        Crafts.Kind knitting = Crafts.byId("knitting");
        assertNotNull(knitting, "knitting, from Wikipedia's outline where Wikidata's trees miss it");
        assertTrue(all.stream().filter(Crafts.Kind::sport).count() > 1500, "every sport with its disciplines, the martial arts: doing, not making");
        assertTrue(all.stream().noneMatch(k -> k.desc().toLowerCase().contains("wine")), "the wine designations the hobby tree leaks into are out");
        long cut = all.stream().filter(k -> !k.keep()).count();
        assertTrue(cut > 60 && cut < 200 && all.stream().filter(k -> !k.keep()).allMatch(k -> !k.cut().isEmpty()), "the operator's four rules cut about a hundred, each with its reason: " + cut);
    }

    @Test
    void aQuestionsKindsAreFoundByNameInEnglishAndJapanese() {
        List<Crafts.Kind> k = Crafts.find("How do I start knitting a sweater, and what do I need?");
        assertTrue(k.stream().anyMatch(x -> x.id().equals("knitting")), k.toString());
        assertTrue(Crafts.find("二風谷イタを作るには").stream().anyMatch(x -> x.id().equals("meti-0601")));
        assertTrue(Crafts.find("what is the capital of Japan").isEmpty());
        assertTrue(Crafts.find("").isEmpty());
    }

    @Test
    void theFieldAppliesOnTheWordsOfDoingAndSuggestsItselfOnAKindsName(@TempDir Path tmp) throws Exception {
        CraftsProfile p = new CraftsProfile();
        assertEquals("crafts", p.name());
        assertTrue(p.applies("How do I weave a medieval tapestry, and what do I need to do it today?"));
        assertTrue(p.applies("what materials do I need for silversmithing"));
        assertTrue(p.applies("キルトの作り方と必要な道具"));
        assertFalse(p.applies("Who measured the tooth profiles first?"));
        assertTrue(p.suggests("tell me about knitting"), "a kind named without a word of doing: offered");
        assertFalse(p.suggests("the weather in Osaka tomorrow"));
        assertTrue(p.register().contains("the kit") && p.writerRules().contains("modern stand-in") && p.criticRules().contains("substitute"));
        assertTrue(Profiles.known().stream().anyMatch(x -> x.name().equals("crafts")), "registered");
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String ev = p.runEvidence(store, new Researcher.Ask("How do I start knitting a sweater?", "broad", 0, List.of(), "", List.of(), 0, List.of(), List.of()), ContentPolicy.defaults());
        assertTrue(ev.startsWith("THE KINDS OF DOING THIS ASK NAMES") && ev.toLowerCase().contains("knitting") && ev.contains("https://en.wikipedia.org/wiki/Knitting"), ev);
        assertEquals("", p.runEvidence(store, new Researcher.Ask("the weather", "broad", 0, List.of(), "", List.of(), 0, List.of(), List.of()), ContentPolicy.defaults()));
    }

    /** The rebuild of 2026-10-13: sports with their disciplines under them, martial arts by country, the trades, Japanese names, other names, no class-shaped junk. */
    @Test
    void sportsMartialArtsAndTradesAreInWithTheirChainsAndNames() {
        Crafts.Kind iaido = Crafts.byId("iaido");
        assertNotNull(iaido); assertEquals("居合道", iaido.ja()); assertTrue(iaido.martialArt() && iaido.sport() && !iaido.olympic());
        assertTrue(Crafts.describe(iaido).contains("(a martial art: doing, not making)") && Crafts.describe(iaido).contains("a kind of Weapons"), Crafts.describe(iaido));
        Crafts.Kind judo = Crafts.byId("judo");
        assertNotNull(judo); assertEquals("柔道", judo.ja()); assertTrue(judo.olympic(), "the Olympic flag from Wikidata's entity");
        assertTrue(Crafts.describe(judo).contains(", an Olympic sport)"), Crafts.describe(judo));
        Crafts.Kind target = Crafts.byId("target-archery");
        assertNotNull(target); assertEquals(List.of("modern-competitive-archery"), target.parents().subList(0, 1));
        assertTrue(Crafts.describe(target).contains("a kind of Modern competitive archery") && Crafts.describe(target).contains("under Archery") && Crafts.describe(target).contains("under Shooting sports"), "a discipline under its sport under its family: " + Crafts.describe(target));
        Crafts.Kind fclass = Crafts.byId("f-class");
        assertNotNull(fclass, "F-Class, from the shooting sports page"); assertEquals("fullbore-target-shooting", fclass.parents().get(0));
        for (String id : List.of("precision-rifle-series", "benchrest-shooting", "water-polo", "triathlon", "association-football", "basketball", "kendo", "kyudo", "wudangquan", "karate", "plumber", "carpenters-and-joiners"))
            assertNotNull(Crafts.byId(id), id);
        assertEquals("Football", Crafts.byId(Crafts.byId("association-football").parents().get(0)).en(), "placed where the list places it, not under fantasy sports");
        Crafts.Kind ipsc = Crafts.byId("international-practical-shooting-confederation");
        assertNotNull(ipsc); assertTrue(ipsc.aliases().contains("IPSC"), "the abbreviation beside the link is another name: " + ipsc.aliases());
        Crafts.Kind carpenters = Crafts.byId("carpenters-and-joiners");
        assertTrue(carpenters.trade() && carpenters.designation().startsWith("ISCO-08 7") && carpenters.aliases().contains("carpenter") && carpenters.aliases().contains("joiner"), carpenters.toString());
        assertTrue(Crafts.describe(carpenters).contains("(a trade)") && Crafts.describe(carpenters).contains("also carpenter, joiner"), Crafts.describe(carpenters));
        for (String junk : List.of("women-junior", "woman-junior", "hiking-in-south-africa", "cycling-by-country-region-or-city", "type-of-association-football", "boxing-style", "4-100-metre-freestyle-relay", "men-s-table-tennis", "climbing-youtuber"))
            assertNull(Crafts.byId(junk), junk + " is a class, an event or a place, not a kind of doing");
        assertTrue(Crafts.all().stream().noneMatch(k -> k.en().matches("(?s).*\\bin (the )?[A-Z][a-z].*")), "no place-shaped names (Hiking in South Africa)");
        assertTrue(Crafts.all().stream().filter(k -> !k.ja().isEmpty()).count() > 1500, "Japanese names from Wikidata's entities");
    }

    @Test
    void aSportIsFoundByItsOtherNameAndTheFieldAppliesOnTakingItUp(@TempDir Path tmp) throws Exception {
        assertTrue(Crafts.find("How do I get into PRS and what rifle do I need?").stream().anyMatch(k -> k.id().equals("precision-rifle-series")), "PRS, as written");
        assertTrue(Crafts.find("I want to become a carpenter").stream().anyMatch(k -> k.id().equals("carpenters-and-joiners")), "the occupation inside the ISCO group label");
        assertTrue(Crafts.find("居合道を習うには").stream().anyMatch(k -> k.id().equals("iaido")));
        assertTrue(Crafts.find("the prs of the matter").isEmpty(), "a short capitalised name counts only as written");
        assertTrue(Crafts.find("how do I learn wudang sword").stream().anyMatch(k -> k.id().equals("wudangquan")), "an art by its stem: wudang finds Wudangquan");
        assertTrue(Crafts.find("where can I learn naginata").stream().anyMatch(k -> k.id().equals("naginatajutsu")), "naginata finds Naginatajutsu");
        assertTrue(Crafts.all().stream().noneMatch(k -> k.martialArt() && (("Japan".equals(k.region()) && k.ja().contains("派")) || k.en().matches("(?i).*[ -]ha$"))), "a Japanese branch (派, -ha) is a lineage of its school, not a kind");
        assertNotNull(Crafts.byId("tien-shan-pai"), "in a Chinese name 派 is the school itself: kept");
        CraftsProfile p = new CraftsProfile();
        assertTrue(p.applies("How do I get into PRS shooting?"), "get into + PRS");
        assertTrue(p.applies("What equipment do I need for iaido, and where can I learn it?"), "what equipment + iaido");
        assertTrue(p.applies("居合道の始め方"), "始め方 + 居合道");
        assertFalse(p.applies("How do I start a family tree for a family from Hiroshima?"), "the words of taking up count only with a kind the list names");
        assertFalse(p.applies("how do I start"));
        assertTrue(p.register().contains("where one learns"), p.register()); assertTrue(p.planRules().contains("governing body"), p.planRules());
        assertTrue(p.writerRules().contains("what the law requires"), p.writerRules()); assertTrue(p.criticRules().contains("equipment rules"), p.criticRules());
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String ev = p.runEvidence(store, new Researcher.Ask("What do I need to start iaido?", "broad", 0, List.of(), "", List.of(), 0, List.of(), List.of()), ContentPolicy.defaults());
        assertTrue(ev.contains("居合道") && ev.contains("a martial art: doing, not making") && ev.contains("a discipline is listed under its sport"), ev);
    }

    /** The communities people join (2026-10-13, "imagine someone wants to join the SCA…"): from Wikipedia's categories of reenactment, LARP and HEMA groups. */
    @Test
    void aCommunityPeopleJoinIsInWithItsWayInItsSiteAndWhatItsMembersDo(@TempDir Path tmp) throws Exception {
        Crafts.Kind sca = Crafts.byId("society-for-creative-anachronism");
        assertNotNull(sca); assertTrue(sca.community() && sca.aliases().contains("SCA") && sca.website().startsWith("https://www.sca.org"), sca.toString());
        assertEquals("historical-reenactment", sca.parents().get(0), "a community under what its members do");
        assertTrue(Crafts.describe(sca).contains("(a community people join: it has its rules, the kit its rules demand, and a way in; its site https://www.sca.org/)") && Crafts.describe(sca).contains("its members do Historical reenactment"), Crafts.describe(sca));
        Crafts.Kind kingdom = Crafts.byId("kingdom-of-calontir");
        assertNotNull(kingdom, "a branch of the SCA, under it"); assertEquals("society-for-creative-anachronism", kingdom.parents().get(0));
        assertEquals("live-action-role-playing-game", Crafts.byId("amtgard").parents().get(0));
        assertTrue(Crafts.byId("historical-european-martial-arts").aliases().contains("HEMA"), "Wikidata's other names");
        for (String id : List.of("harvard-world-model-united-nations", "model-united-nations", "mafia-party-game", "medieval-times", "pennsic-war", "foam-weapon", "das-bus", "cosmeston-medieval-village"))
            assertNull(Crafts.byId(id), id + " is a club of another kind, a game, a business, an event, a prop or a museum: not a community");
        assertTrue(Crafts.byId("airsoft").sport() && !Crafts.byId("airsoft").community(), "a kind of doing in a group category stays a kind");
        assertTrue(Crafts.byId("combat-hopak").martialArt(), "an art in a group category is an art");
        assertTrue(Crafts.find("How do I join the SCA?").stream().anyMatch(k -> k.id().equals("society-for-creative-anachronism")));
        CraftsProfile p = new CraftsProfile();
        assertTrue(p.applies("How do I join the SCA and what armour do I need for heavy combat?"), "join + a named community");
        assertTrue(p.applies("I'm in the SCA and want better equipment: can I make my own with modern materials?"));
        assertFalse(p.applies("how do I join a gym"), "the words of joining count only with a kind the list names");
        assertTrue(p.register().contains("how its members make their own") && p.planRules().contains("how to join") && p.writerRules().contains("the rules the equipment must meet") && p.criticRules().contains("its own rulebook"));
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String ev = p.runEvidence(store, new Researcher.Ask("I want to join the SCA and make my own armour", "broad", 0, List.of(), "", List.of(), 0, List.of(), List.of()), ContentPolicy.defaults());
        assertTrue(ev.contains("a community people join") && ev.contains("https://www.sca.org") && ev.contains("a community under what its members do"), ev);
        String armour = p.runEvidence(store, new Researcher.Ask("What gauge steel does an SCA helm need?", "broad", 0, List.of(), "", List.of(), 0, List.of(), List.of()), ContentPolicy.defaults());
        assertTrue(armour.contains("16 ga 1.52 mm") && armour.contains("14 ga 1.90 mm") && armour.contains("never compute"), "the gauge table rides with an armour ask: " + armour);
        assertFalse(p.runEvidence(store, new Researcher.Ask("What do I need to start iaido?", "broad", 0, List.of(), "", List.of(), 0, List.of(), List.of()), ContentPolicy.defaults()).contains("GAUGE TO MILLIMETRES"), "and not with an ask that names no gauge, armour, helm or plate");
        assertTrue(p.writerRules().contains("your kingdom") && p.writerRules().contains("never computed"), p.writerRules());
    }

    /** The cut, 2026-10-13, on the operator's word: events and shows, the classification labels, fantasy sports and video-game genres, trades that are no craft. */
    @Test
    void theCutIsByRuleWithItsReasonAndFindSkipsIt() {
        for (String id : List.of("puppy-bowl", "jeopardy", "cooper-s-hill-cheese-rolling-and-wake", "invasion-games", "net-and-wall-games", "fantasy-baseball", "fantasy-sports", "first-person-shooter", "construction-supervisors"))
            { Crafts.Kind k = Crafts.byId(id); assertNotNull(k, id); assertFalse(k.keep(), id + " is cut"); assertFalse(k.cut().isEmpty(), id + " says why"); }
        assertTrue(Crafts.byId("puppy-bowl").cut().contains("TV programme") && Crafts.describe(Crafts.byId("puppy-bowl")).contains("[cut: a TV programme, not a kind of doing]"));
        for (String id : List.of("esports", "speedrunning", "video-gaming", "bicycle", "equines", "aircraft", "combination-puzzle", "barrel-racing", "competition-climbing", "jousting", "pinball", "craft-and-related-trades", "chess"))
            { Crafts.Kind k = Crafts.byId(id); assertNotNull(k, id); assertTrue(k.keep(), id + " stays: " + k.cut()); }
        assertTrue(Crafts.find("what happens at the Puppy Bowl").isEmpty(), "a cut kind is not found by name");
        assertTrue(Crafts.find("how do I get into esports").stream().anyMatch(k -> k.id().equals("esports")));
    }
}
