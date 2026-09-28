package org.researchzosho.records;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.Profiles;
import org.researchzosho.librarian.SourceTier;
import org.researchzosho.tools.RecordSearchTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.ArrayList;
import org.junit.jupiter.api.Assertions;
import java.time.Year;
import java.util.Map;
/** Record sources are data: the address and the citation are templates, a missing part drops out, and the owner's file adds a country without a release. */
class RecordSourcesTest {

    private static final ObjectMapper J = new ObjectMapper();

    private static RecordSource source(String json) throws Exception { return RecordSources.parse(J.readTree(json), false); }

    private static final String PAPERS = """
            {"id": "papers", "name": "Old Papers", "holds": "newspaper pages", "kind": "newspaper", "countries": ["XX"], "languages": ["xx"], "from": 1800, "to": 1950,
             "url": "https://papers.example/api?q={query}[&dates={from}/{to}]&n={limit}", "items": "/results",
             "title": "{/paper}[, page {/page}]", "date": "{/date}", "link": "{/id}", "snippet": "{/text/0}",
             "where": "{/paper}[, {/date}][, page {/page}], Old Papers"}""";

    @Test
    void theAddressDropsAnOptionalPartWhoseValueIsMissingAndEncodesTheQuery() throws Exception {
        RecordSource s = source(PAPERS);
        assertEquals("https://papers.example/api?q=%22Arthur+Ellis%22+fitter&n=8", RecordSources.address(s, "\"Arthur Ellis\" fitter", 0, 0, 8));
        assertEquals("https://papers.example/api?q=%E9%AB%98%E5%B3%B0&dates=1890/1922&n=5", RecordSources.address(s, "高峰", 1890, 1922, 5));
        assertEquals("https://papers.example/api?q=x&n=5", RecordSources.address(s, "x", 1890, 0, 5), "half a range is no range for a source that takes both");
        RecordSource names = source(PAPERS.replace("q={query}", "[given={query:rest}&]family={query:last}"));
        assertEquals("https://papers.example/api?given=Jokichi&family=Takamine&n=3", RecordSources.address(names, "\"Jokichi Takamine\"", 0, 0, 3), "a collection that takes the names apart");
        assertEquals("https://papers.example/api?family=Takamine&n=3", RecordSources.address(names, "Takamine", 0, 0, 3), "a family name alone: no given-name part at all");
        RecordSource bare = source(PAPERS.replace("q={query}", "q=%22{query:bare}%22"));
        assertEquals("https://papers.example/api?q=%22Jansen+smid%22&n=3", RecordSources.address(bare, "\"Jansen smid\"", 0, 0, 3));
    }

    @Test
    void aJsonAnswerBecomesHitsWithACitationThatLeavesOutWhatTheHitLacks() throws Exception {
        RecordSource s = source(PAPERS);
        String body = """
                {"results": [
                  {"paper": "The Dunedin Star", "date": "1952-03-04T00:00:00Z", "page": 7, "id": "https://papers.example/p/1", "text": ["Mr <em>Arthur Ellis</em>, fitter,\\n arrived"]},
                  {"paper": "The Echo", "id": "//papers.example/p/2"},
                  {"nothing": true}]}""";
        List<RecordSources.Hit> hits = RecordSources.search(s, "Arthur Ellis", 0, 0, 8, url -> body);
        assertEquals(2, hits.size(), "an item with neither a title nor a link is not a hit");
        assertEquals(new RecordSources.Hit("The Dunedin Star, page 7", "1952-03-04", "https://papers.example/p/1", "Mr Arthur Ellis, fitter, arrived", "The Dunedin Star, 1952-03-04T00:00:00Z, page 7, Old Papers"), hits.get(0));
        assertEquals("The Echo, Old Papers", hits.get(1).where());
        assertEquals("https://papers.example/p/2", hits.get(1).link());
        String shown = RecordSources.render(s, "Arthur Ellis", hits);
        assertTrue(shown.contains("cite as: The Echo, Old Papers") && shown.contains("web_fetch the link and read the record"), shown);
        assertTrue(RecordSources.render(s, "Arthur Ellis", List.of()).contains("That is a result: note it"), "a search that found nothing is said to be a result");
    }

    @Test
    void aRepositoryHitShowsItsTopicsAndNoTokenMeansNoHeader() throws Exception {
        RecordSource github = RecordSources.all(null).stream().filter(x -> x.id().equals("github-repos")).findFirst().orElseThrow();
        String body = """
                {"items": [{"full_name": "someone/gedcom-rs", "html_url": "https://github.com/someone/gedcom-rs", "description": "A GEDCOM 7 parser", "pushed_at": "2026-09-01T10:00:00Z",
                  "created_at": "2025-11-02T00:00:00Z", "stargazers_count": 3, "language": "Rust", "license": {"spdx_id": "MIT"}, "topics": ["genealogy", "gedcom", "family-history"]}]}""";
        List<RecordSources.Hit> hits = RecordSources.search(github, "gedcom", 0, 0, 5, url -> body);
        assertEquals("A GEDCOM 7 parser · topics: genealogy, gedcom, family-history", hits.get(0).snippet());
        LocalDate today = LocalDate.of(2026, 9, 19);
        assertEquals(" (6 months ago)", RecordSources.age("2026-03-08T10:00:00Z", today), "the arithmetic a model got wrong: it called this inactive for over a year");
        assertEquals(" (5 days ago)", RecordSources.age("2026-09-14", today));
        assertEquals(" (3 years ago)", RecordSources.age("2023-03-03T00:00:00Z", today));
        assertEquals("", RecordSources.age("1887-08-11", today), "an old record is not given an age");
        assertEquals("", RecordSources.age("1911", today));
        // a login that lands on an address carrying the token
        assertEquals("AbC123_tokenValue-xyz", RecordSources.tokenFrom("https://www.geni.com/platform/oauth/auth_success#access_token=AbC123_tokenValue-xyz&expires_in=86400"));
        assertEquals(24, RecordSources.hoursFrom("…#access_token=x&expires_in=86400"));
        assertEquals("AbC123_tokenValue-xyz", RecordSources.tokenFrom("  AbC123_tokenValue-xyz\n"), "or the token pasted by itself");
        assertEquals("", RecordSources.tokenFrom("https://www.geni.com/home"), "an address with no token saves nothing");
        // an archive that writes the caller's API key into every link it returns: the key never reaches a report
        assertEquals("https://www.europeana.eu/item/92023/x", RecordSources.withoutTracking("https://www.europeana.eu/item/92023/x?utm_source=api&utm_medium=api&utm_campaign=THEKEY"));
        assertEquals("https://archive.org/details/y?q=%22Caroline+Hitch%22#page=3", RecordSources.withoutTracking("https://archive.org/details/y?utm_source=a&q=%22Caroline+Hitch%22#page=3"));
        assertEquals("https://example.org/plain", RecordSources.withoutTracking("https://example.org/plain"));
        assertEquals("someone/gedcom-rs, Rust, licence MIT, 3 stars, last push 2026-09-01T10:00:00Z, created 2025-11-02T00:00:00Z", hits.get(0).where());
    }

    @Test
    void anXmlAnswerIsReadByElementNameAndADoctypeIsRefused() throws Exception {
        RecordSource s = source("""
                {"id": "sru", "name": "A Library", "kind": "newspaper", "url": "https://sru.example/?query={query}&n={limit}", "format": "xml", "items": "srw:record",
                 "title": "{dc:title}", "date": "{dc:date}", "link": "{dc:identifier}", "where": "{dc:title}[, {dc:publisher}][, {dc:date}], A Library"}""");
        String body = """
                <?xml version="1.0"?><r xmlns:srw="s" xmlns:dc="d"><srw:record><dc:title>Rechtszaken</dc:title><dc:date>1910/07/23 00:00:00</dc:date><dc:identifier>http://resolver.example/1</dc:identifier></srw:record></r>""".strip();
        List<RecordSources.Hit> hits = RecordSources.search(s, "Jansen", 0, 0, 5, url -> body);
        assertEquals(1, hits.size());
        assertEquals("1910/07/23", hits.get(0).date());
        assertEquals("Rechtszaken, 1910/07/23 00:00:00, A Library", hits.get(0).where());
        assertThrows(Exception.class, () -> RecordSources.search(s, "x", 0, 0, 5, url -> "<!DOCTYPE r [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><r>&e;</r>"));
    }

    @Test
    void theOwnersFileAddsASourceAndReplacesABuiltInOneAndABrokenFileChangesNothing(@TempDir Path tmp) throws Exception {
        List<RecordSource> builtIn = RecordSources.all(null);
        assertTrue(builtIn.size() >= 10 && builtIn.stream().allMatch(RecordSource::builtIn));
        // each field has its own collections: a family run is not shown a package index, a software run not a parish newspaper
        List<String> family = RecordSources.forFields(builtIn, List.of("genealogy")).stream().map(RecordSource::id).toList(), software = RecordSources.forFields(builtIn, List.of("software")).stream().map(RecordSource::id).toList();
        assertTrue(family.contains("loc-newspapers") && family.contains("ndl-fulltext") && !family.contains("github-repos") && !family.contains("npm"), family.toString());
        assertTrue(software.contains("github-repos") && software.contains("github-active") && software.contains("crates") && !software.contains("loc-newspapers"), software.toString());
        RecordSource github = builtIn.stream().filter(x -> x.id().equals("github-repos")).findFirst().orElseThrow();
        assertTrue(RecordSources.usable(github) && !github.needsKey(), "GitHub's token only raises a limit: the source works without it");
        assertEquals("https://api.github.com/search/repositories?q=gedcom+topic%3Agenealogy+pushed%3A%3E%3D2025-01-01&per_page=5", RecordSources.address(github, "gedcom topic:genealogy", 2025, 0, 5));
        for (RecordSource s : builtIn) {
            assertFalse(s.holds().isBlank() || s.where().isBlank() || s.fields().isEmpty(), s.id());
            assertTrue(RecordSources.address(s, "a name", 1900, 1910, 5).startsWith("https://") && !RecordSources.address(s, "a name", 1900, 1910, 5).matches(".*\\{(query|limit|from|to)[:}].*"), s.id());
        }
        assertTrue(builtIn.stream().anyMatch(s -> s.countries().contains("JP")) && builtIn.stream().anyMatch(s -> s.countries().contains("US")));
        Path own = tmp.resolve("record-sources.json");
        Files.writeString(own, "[" + PAPERS + ", {\"id\": \"wikidata\", \"name\": \"Mine\", \"url\": \"https://mine.example/?q={query}\"}, {\"id\": \"no-query\", \"url\": \"https://x.example/\"}]");
        List<RecordSource> all = RecordSources.all(own);
        assertEquals(builtIn.size() + 1, all.size(), "one added, one replaced, one without {query} left out");
        assertEquals("Mine", all.stream().filter(s -> s.id().equals("wikidata")).findFirst().orElseThrow().name());
        assertFalse(all.stream().filter(s -> s.id().equals("papers")).findFirst().orElseThrow().builtIn());
        Files.writeString(own, "[ this is not json");
        assertEquals(builtIn.size(), RecordSources.all(own).size());
    }

    @Test
    void aPageOnASourceThatHoldsRecordsIsAPrimarySourceAndAnIndexIsNot() {
        assertEquals(SourceTier.primary, SourceTier.of("https://www.loc.gov/resource/sn86091092/1920-12-17/ed-1/?sp=15"), "before this it was 'reference', as any .gov");
        assertEquals(SourceTier.primary, SourceTier.of("https://dl.ndl.go.jp/pid/1056555"));
        assertEquals(SourceTier.primary, SourceTier.of("http://resolver.kb.nl/resolve?urn=MMNHA03:178979037"));
        assertNotEquals(SourceTier.primary, SourceTier.of("https://www.wikidata.org/wiki/Q910647"), "an index points to a record");
        assertNotEquals(SourceTier.primary, SourceTier.of("https://www.archives.go.jp/"));
    }

    @Test
    void theToolListsItsSourcesTheQuestionsOwnFirstAndSaysPlainlyWhatWentWrong() throws Exception {
        List<RecordSource> ordered = RecordSources.ordered(List.of("ja"));
        assertTrue(ordered.get(0).languages().contains("ja"), ordered.get(0).id());
        int firstEverywhere = -1, lastMine = -1;
        for (int i = 0; i < ordered.size(); i++) { if (ordered.get(i).languages().contains("ja")) lastMine = i; else if (firstEverywhere < 0) firstEverywhere = i; }
        assertTrue(lastMine < firstEverywhere, "every Japanese source comes before the rest");
        assertTrue(ordered.stream().allMatch(RecordSources::usable), "a source whose key is not set is not offered (whatever keys this machine's own settings hold)");

        RecordSource s = source(PAPERS);
        RecordSearchTool tool = new RecordSearchTool(List.of(s), url -> { if (url.contains("boom")) throw new IllegalStateException("HTTP 503"); return "{\"results\": []}"; });
        assertTrue(tool.description().contains("- papers: Old Papers [XX] 1800-1950. newspaper pages."), tool.description());
        assertEquals("[\"papers\"]", tool.parametersSchema(J).at("/properties/source/enum").toString());
        assertTrue(tool.execute(J.readTree("{\"source\": \"papers\", \"query\": \"Ellis\"}")).startsWith("no records found in Old Papers for: Ellis"));
        assertTrue(tool.execute(J.readTree("{\"source\": \"papers\", \"query\": \"boom\"}")).contains("did not answer (HTTP 503). This is not a search that found nothing"));
        assertTrue(tool.execute(J.readTree("{\"source\": \"nowhere\", \"query\": \"x\"}")).contains("The sources are: papers"));

        // several words that find nothing: the name alone is tried before it counts as not found
        List<String> asked = new ArrayList<>(); List<RecordSearchTool.Searched> heard = new ArrayList<>();
        RecordSearchTool names = new RecordSearchTool(List.of(s), url -> { asked.add(url); return url.contains("q=%E9%AB%98%E5%B3%B0%E8%AD%B2%E5%90%89&") ? "{\"results\": [{\"paper\": \"現代発明家伝\", \"id\": \"https://papers.example/1\"}]}" : "{\"results\": []}"; }).listen(heard::add);
        String out = names.execute(J.readTree("{\"source\": \"papers\", \"query\": \"高峰譲吉 夫人 子供\"}"));
        assertTrue(out.startsWith("Nothing in Old Papers has all of these words together: 高峰譲吉 夫人 子供. Searched again for 高峰譲吉 alone:") && out.contains("現代発明家伝"), out);
        assertEquals(2, asked.size());
        assertEquals(1, heard.size(), "one search is heard, the one that counts");
        assertEquals("高峰譲吉", heard.get(0).query());
        names.execute(J.readTree("{\"source\": \"papers\", \"query\": \"\\\"Artur Elis\\\" fitter Dunedin\"}"));
        assertEquals("\"Artur Elis\"", heard.get(1).query(), "what is recorded as not found is the name, not the name with two more words");
        assertTrue(heard.get(1).hits().isEmpty());
        assertEquals("inventor:\"Jokichi Takamine\"", RecordSearchTool.firstTerm("inventor:\"Jokichi Takamine\""));
    }

    @Test
    void anAddressWhoseSignsWereEncodedByTheBrowserStillGivesItsToken() {
        String landed = "https://www.example.org//oauth/auth_success#access_token%3DAbCdEf0123456789xyz%26expires_in%3D86400";
        Assertions.assertEquals("AbCdEf0123456789xyz", RecordSources.tokenFrom(landed));
        Assertions.assertEquals(24, RecordSources.hoursFrom(landed));
        Assertions.assertEquals("AbCdEf0123456789xyz", RecordSources.tokenFrom("https://www.example.org/ok#access_token=AbCdEf0123456789xyz&expires_in=86400"));
    }

    @Test
    void aRecordIsMarkedWithWhatItCarriesOfTheQuestionBesidesTheName() throws Exception {
        String question = "髙橋源三郎 (also written Takahashi Genzaburo; born 1872 in 広島; child of 髙橋正一 and 髙橋ハル): what records exist for this person?";
        List<String> said = RecordSources.saidOf(question);
        assertTrue(said.containsAll(List.of("1872", "広島", "髙橋正一", "髙橋ハル", "Takahashi Genzaburo")), said.toString());
        RecordSource s = source("{\"id\":\"t\",\"name\":\"A library\",\"kind\":\"catalog\",\"url\":\"https://x.example/?q={query}\",\"holds\":\"books\"}");
        List<RecordSources.Hit> hits = List.of(
                new RecordSources.Hit("髙橋源三郎氏の広島時代", "1930", "https://x.example/1", "明治5年生まれ", ""),
                new RecordSources.Hit("鉄道技術論文 髙橋源三郎", "2017", "https://x.example/2", "JR東日本", ""));
        String shown = RecordSources.render(s, "髙橋源三郎", hits, said);
        assertTrue(shown.contains("1. 髙橋源三郎氏の広島時代 — 1930\n   https://x.example/1\n   明治5年生まれ\n   also carries what the question says of this person: 広島"), shown);
        assertTrue(shown.contains("2. 鉄道技術論文 髙橋源三郎 — 2017\n   https://x.example/2\n   JR東日本\n   matches the name only"), shown);
        assertTrue(shown.contains("A record that matches the name only is a record of somebody of that name"), shown);
        assertFalse(RecordSources.render(s, "髙橋源三郎", hits).contains("matches the name only"), "with nothing said of the person, nothing is marked");
        assertTrue(RecordSources.saidOf("What is the boiling point of ethanol?").isEmpty());
    }

    @Test
    void theCollectionsAreChosenByThePersonsYears() throws Exception {
        RecordSource scans = source("{\"id\":\"scans\",\"name\":\"Old scans\",\"kind\":\"scan\",\"url\":\"https://x.example/?q={query}\",\"holds\":\"books\",\"from\":1868,\"to\":1970}");
        RecordSource papers = source("{\"id\":\"papers\",\"name\":\"Papers\",\"kind\":\"catalog\",\"url\":\"https://y.example/?q={query}\",\"holds\":\"papers\",\"from\":1880}");
        RecordSource trees = source("{\"id\":\"trees\",\"name\":\"Trees\",\"kind\":\"tree\",\"url\":\"https://z.example/?q={query}\",\"holds\":\"trees\"}");
        List<RecordSource> all = List.of(scans, papers, trees);
        assertArrayEquals(new int[]{1973, Year.now().getValue()}, RecordSources.lived("Endo Mari (also written 遠藤まり; born 1973; child of X): what records exist?"));
        assertArrayEquals(new int[]{1852, 1928}, RecordSources.lived("Endo Genzaburo (born 1852 in 伊勢; died 1928 in 東京): who were the parents?"));
        assertNull(RecordSources.lived("What is the boiling point of ethanol?"));
        assertEquals(List.of(papers, trees), RecordSources.forYears(all, RecordSources.lived("X (born 1973): …")), "scans that end in 1970 cannot hold a person born in 1973");
        assertEquals(List.of(scans, papers, trees), RecordSources.forYears(all, RecordSources.lived("X (born 1852; died 1928): …")), "papers from 1880 on can tell of somebody who died in 1928");
        assertEquals(List.of(trees), RecordSources.forYears(all, RecordSources.lived("X (born 1700; died 1760): …")), "scans from 1868 and papers from 1880 both begin more than a lifetime after 1760");
        assertEquals(List.of(scans, trees), RecordSources.forYears(all, RecordSources.lived("X (born 1760; died 1810): …")), "scans from 1868 begin within sixty years of 1810, papers from 1880 do not");
        assertEquals(all, RecordSources.forYears(all, null), "no birth year: nothing is left out");
        String shown = new RecordSearchTool(all, u -> "", List.<String>of(), RecordSources.lived("X (born 1973): …")).description();
        assertTrue(shown.contains("Left out, because their years and this person's do not meet: scans (1868-1970).") && !shown.contains("- scans:"), shown);
        // the years are read by the fields of the run the tool is given to, and the tool is narrowed then
        assertNull(RecordSources.lived("X (born 1973): …", List.of()), "a run of no field that reads dates reads none");
        assertNull(RecordSources.lived("X (born 1973): …", List.of(Profiles.named("science"))));
        assertArrayEquals(new int[]{1973, Year.now().getValue()}, RecordSources.lived("X (born 1973): …", List.of(Profiles.named("genealogy"))));
        assertEquals(shown, new RecordSearchTool(all, u -> "", List.<String>of()).forYears(RecordSources.lived("X (born 1973): …", List.of(Profiles.named("genealogy")))).description());
        assertFalse(new RecordSearchTool(all, u -> "", List.<String>of()).forYears(null).description().contains("Left out"));

        // a run of another field is not told of the family collections it would not have searched anyway
        RecordSource oldPapers = source("{\"id\":\"old-papers\",\"name\":\"Old papers\",\"kind\":\"newspaper\",\"url\":\"https://p.example/?q={query}\",\"holds\":\"newspapers\",\"from\":1850,\"to\":1900,\"fields\":[\"genealogy\"]}");
        RecordSource repos = source("{\"id\":\"repos\",\"name\":\"Repositories\",\"kind\":\"catalog\",\"url\":\"https://r.example/?q={query}\",\"holds\":\"code\",\"fields\":[\"software\"]}");
        String software = new RecordSearchTool(List.of(oldPapers, repos), u -> "", List.<String>of(), RecordSources.lived("Who wrote the parser (born 1990)?")).forFields(List.of("software")).description();
        assertTrue(software.contains("- repos:") && !software.contains("old-papers"), software);
    }

    @Test
    void aSiteWithNoSearchTheLibraryCanMakeIsALinkFilledFromWhatItHolds(@TempDir Path tmp) throws Exception {
        Path none = tmp.resolve("none.json");
        List<RecordSources.SearchLink> links = RecordSources.links(none);
        assertEquals(List.of("familysearch", "ancestry", "myheritage", "geneanet", "findagrave", "compgen"), links.stream().map(RecordSources.SearchLink::id).toList());
        assertTrue(RecordSources.all(none).stream().noneMatch(x -> links.stream().anyMatch(l -> l.id().equals(x.id()))), "the runs never search a link");
        RecordSources.SearchLink fs = links.get(0);
        assertEquals("free with an account", fs.accessText());
        assertEquals("https://www.familysearch.org/search/record/results?q.givenName=Tom&q.surname=Hale&q.birthLikeDate.from=1850&q.birthLikeDate.to=1850",
                RecordSources.filled(fs, Map.of("given", "Tom", "family", "Hale", "born", "1850")), "a year that is not known drops out with its part");
        assertNull(RecordSources.filled(fs, Map.of("given", "Tom", "born", "1850")), "without the family name there is no search to open");
        RecordSources.SearchLink grave = links.stream().filter(l -> l.id().equals("findagrave")).findFirst().orElseThrow();
        assertEquals("https://www.findagrave.com/memorial/search?firstname=Isamu+Tom&lastname=Hart&deathyear=1931",
                RecordSources.filled(grave, Map.of("given", "Isamu Tom", "family", "Hart", "died", "1931")));
        Files.writeString(tmp.resolve("own.json"), "[{\"id\": \"parish\", \"kind\": \"link\", \"name\": \"The parish registers\", \"access\": \"free\", \"url\": \"https://parish.example/find?surname={family}\"}]");
        assertEquals("The parish registers", RecordSources.links(tmp.resolve("own.json")).getLast().name(), "the owner adds a site in the same file");
    }
}
