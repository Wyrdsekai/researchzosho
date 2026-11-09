package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.researchzosho.tools.CustomSearchTool;
import org.researchzosho.tools.ExaSearchTool;
import org.researchzosho.tools.PubMed;
import org.researchzosho.tools.SemanticScholarTool;
import org.researchzosho.tools.TavilySearchTool;
import org.researchzosho.tools.WebSearchTool;
import org.researchzosho.tools.Firecrawl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The settings a library has, as one list the command line, the web page and the chat all read from (0.5.5): each with its kind, what it is
 * for and an example. A secret is never shown back, only whether it is set and how it ends. Every setting lives in the library's config
 * file ({@link Config}); the service reads the file live, so a change takes effect without a restart, except where a note says otherwise.
 */
public final class Settings {
    private Settings() { }

    public enum Kind { text, secret, address, number, choice }

    /** One setting: its key, its kind, what it does in a sentence, an example value, and for a choice the values allowed. */
    public record Setting(String key, Kind kind, String what, String example, List<String> choices) {
        Setting(String key, Kind kind, String what, String example) { this(key, kind, what, example, List.of()); }
        /** The key without the common prefix, lower-case: {@code brave_key}. */
        public String shortName() { return key.replaceFirst("^RESEARCHZOSHO_", "").toLowerCase(Locale.ROOT); }
    }

    static final List<Setting> KNOWN = List.of(
            // the model
            new Setting("RESEARCHZOSHO_DRIVE", Kind.address, "The model server's address (OpenAI-style). Research runs and the chat use it.", "http://localhost:8080"),
            new Setting("RESEARCHZOSHO_MODEL", Kind.text, "The model's name on that server, when the server holds more than one.", "qwen3.8-27b"),
            new Setting("RESEARCHZOSHO_API_KEY", Kind.secret, "The key the model server wants, when it wants one (Anthropic, OpenRouter, OpenAI…).", ""),
            new Setting("RESEARCHZOSHO_ANTHROPIC_EFFORT", Kind.choice, "With drive = https://api.anthropic.com: how hard Claude thinks on each call. Unset: the model's own default.", "", List.of("low", "medium", "high", "xhigh", "max")),
            new Setting("RESEARCHZOSHO_OPENAI_EFFORT", Kind.choice, "With drive = https://api.openai.com: how hard the model reasons on each call (each model takes some of these). Unset: the model's own default.", "", List.of("none", "minimal", "low", "medium", "high", "xhigh", "max")),
            // best judgement
            new Setting("RESEARCHZOSHO_AUTO", Kind.choice, "Whether the library chooses the model itself for a run that names none, once two models are saved (researchzosho models auto says what it would pick and why). Unset: on.", "", List.of("on", "off")),
            new Setting("RESEARCHZOSHO_AUTO_WAIT_MINUTES", Kind.number, "When somebody is waiting (a quick ask, a chat turn), the minutes best judgement may let a run take; a slower model is passed over. Unset: 5.", "5"),
            new Setting("RESEARCHZOSHO_AUTO_MAX_DOLLARS", Kind.number, "The most best judgement may let one run be estimated to cost, in dollars. Unset: no limit.", "2"),
            new Setting("RESEARCHZOSHO_AUTO_LOCAL_ONLY", Kind.choice, "Keep every run best judgement chooses on a local model, never a hosted one. Unset: off.", "", List.of("on", "off")),
            new Setting("RESEARCHZOSHO_LANGUAGE", Kind.text, "The language every report is written in, whatever language the question is asked in: English, Japanese, German… Unset: a report is written in its question's language. Each person can read reports in their own language besides (researchzosho settings read-in).", "Japanese"),
            new Setting(ReportOptions.LENGTH_SETTING, Kind.text, "How long a report is unless its run asks otherwise: short (about 400 words), medium (about 1,200), long (about 3,000), or a number of words. Unset: as long as the question needs.", "medium"),
            new Setting(ReportOptions.CITATIONS_SETTING, Kind.choice, "How the sources at the end of a report are written unless its run asks otherwise. The numbers in the text stay, so every cited sentence can still be checked. Unset: numbered.", "", List.of("numbered", "apa", "mla", "chicago", "ieee")),
            new Setting("RESEARCHZOSHO_JUDGE_DRIVE", Kind.address, "A second model server for the checks (the judge); the main one when unset.", "http://localhost:8081"),
            new Setting("RESEARCHZOSHO_JUDGE_MODEL", Kind.text, "The judge model's name on that server.", ""),
            new Setting("RESEARCHZOSHO_EMBED", Kind.address, "The embeddings server, for search by meaning; off when unset.", "http://localhost:30001"),
            new Setting("RESEARCHZOSHO_VISION_DRIVE", Kind.address, "A model server that reads pictures; the main one when unset.", ""),
            new Setting("RESEARCHZOSHO_VISION_MODEL", Kind.text, "The picture-reading model's name.", ""),
            new Setting("RESEARCHZOSHO_PICTURES", Kind.choice, "Pictures in research runs: the workers' image_search and image_judge, and the picture step after them for the people, organisations and places the findings name. off leaves them out of every run. Unset: on.", "", List.of("on", "off")),
            new Setting("RESEARCHZOSHO_DESCRIBE_PICTURES", Kind.choice, "Whether the model that sees describes the pictures of a document you add, the first twenty of each, once; off keeps them with their alt text only. Unset: on.", "", List.of("on", "off")),
            // web search
            new Setting("RESEARCHZOSHO_SERPER_KEY", Kind.secret, "A Serper key (serper.dev): Google's own results, used first for web search when set. Paid per search after the free credits, about a tenth of a cent each, and a research run makes tens of searches; the key is sent only to Serper. researchzosho search status shows the month's tally.", ""),
            new Setting("RESEARCHZOSHO_SERPAPI_KEY", Kind.secret, "A SerpApi key (serpapi.com): Google's own results, used first when set and no Serper key is. Paid per search after the free hundred a month, about one and a half cents each; the key is sent only to SerpApi.", ""),
            new Setting(WebSearchTool.SERPER_LIMIT, Kind.number, "The most searches the library makes through Serper in a calendar month; at the limit the web search goes on to the next way of searching until the month turns, and says so once. No limit unless set.", "2500"),
            new Setting(WebSearchTool.SERPAPI_LIMIT, Kind.number, "The most searches the library makes through SerpApi in a calendar month; at the limit the web search goes on to the next way of searching until the month turns, and says so once. No limit unless set.", "250"),
            new Setting("RESEARCHZOSHO_FIRECRAWL_KEY", Kind.secret, "A Firecrawl key (firecrawl.dev): a page a plain fetch cannot read, built by scripts or behind a bot wall or a 403, is read through Firecrawl's browser instead. Paid per page after the free credits, about a tenth of a cent each, at most twenty pages a run; the key is sent only to Firecrawl.", ""),
            new Setting(Firecrawl.LIMIT, Kind.number, "The most pages the library reads through Firecrawl in a calendar month; at the limit a page a plain fetch cannot read is looked for in the archives instead until the month turns, and the fetch says so. No limit unless set.", "1000"),
            new Setting("RESEARCHZOSHO_RENTCAST_KEY", Kind.secret, "A RentCast key (rentcast.io): homes for sale and for rent across the United States, read by the home search when set, one call per kind of listing per search. The first fifty calls a month are free, then each is paid; the key is sent only to RentCast. researchzosho search status shows the month's count.", ""),
            new Setting(HomeJapanRecords.KEY, Kind.secret, "A key for MLIT's 不動産情報ライブラリ (free; apply on reinfolib.mlit.go.jp, about five working days): a home search in Japan reads the prices paid nearby (不動産取引価格情報), the nearest 地価公示 point and the flood, sediment and danger areas for each card. The key is sent only to the ministry's host, in a header; researchzosho search status counts the calls. Without it the search reads the listings alone.", ""),
            new Setting("RESEARCHZOSHO_CENSUS_KEY", Kind.secret, "A Census Bureau API key (free, from api.census.gov/data/key_signup.html): a home's card gives its Census tract's figures when set, the median household income and age, the owners' and renters' shares, the median home value and rent, from the American Community Survey. One call per tract, kept a year.", ""),
            new Setting("RESEARCHZOSHO_BRAVE_KEY", Kind.secret, "A Brave Search API key (brave.com/search/api); used for web search before SearXNG, after a Google key when one is set.", ""),
            new Setting(Candidates.KEY, Kind.secret, "A YouTube Data API key (in the Google Cloud console: make a project, enable the YouTube Data API v3 under APIs and services, make an API key under Credentials). The candidate search for a video you remember asks YouTube for videos in date order with it, which YouTube's own search never gives. Free: 10,000 units a day, a search page costs 100, so about ninety pages a day; the key is sent only to Google.", ""),
            new Setting(Candidates.PAGES, Kind.number, "The most Data API search pages one candidate search makes, 100 units each of the day's 10,000. Unset: 20.", "20"),
            new Setting(WebSearchTool.BRAVE_LIMIT, Kind.number, "The most searches the library makes through Brave in a calendar month, for a paid plan; at the limit the web search goes on to SearXNG or the fallback until the month turns, and says so once. No limit unless set.", "1000"),
            new Setting("RESEARCHZOSHO_SEARXNG", Kind.address, "A SearXNG address; the fallback behind Brave, or the web search when there is no key.", "http://localhost:8888"),
            new Setting("RESEARCHZOSHO_FALLBACK_SEARCH", Kind.choice, "The built-in fallback search (Wikipedia and papers) when nothing else answers.", "on", List.of("on", "off")),
            // the search places of 0.7.0
            new Setting(PubMed.KEY, Kind.secret, "An NCBI API key (free, from the API Key Management part of your NCBI account's settings page): PubMed and PubMed Central answer the pubmed place ten times a second instead of three. The place works without it; the key is sent only to NCBI.", ""),
            new Setting(SemanticScholarTool.KEY, Kind.secret, "A Semantic Scholar API key (free; asked for with the form on semanticscholar.org/product/api and sent by email): the semantic-scholar place gets one call a second of its own instead of the shared allowance of every program without a key, which is busy at times. The key is sent only to Semantic Scholar.", ""),
            new Setting(ExaSearchTool.KEY, Kind.secret, "An Exa key (dashboard.exa.ai): the exa place, the web searched by meaning, each page with the passages that match. Paid from the account's credits, $7 per 1,000 searches as read on 2026-11-04; the key is sent only to Exa.", ""),
            new Setting(ExaSearchTool.LIMIT, Kind.number, "The most searches the library makes through Exa in a calendar month; at the limit Exa is left off until the month turns. Unset: 714, which keeps Exa under $5 a month at its price as read on 2026-11-04. 0 turns it off.", "714"),
            new Setting(TavilySearchTool.KEY, Kind.secret, "A Tavily key (app.tavily.com): the tavily place, the web with the passages of each page that answer the query. 1,000 searches a month are free, then each is paid; the key is sent only to Tavily.", ""),
            new Setting(TavilySearchTool.LIMIT, Kind.number, "The most searches the library makes through Tavily in a calendar month; at the limit Tavily is left off until the month turns. Unset: 1,000, the free allowance as read on 2026-11-04. 0 turns it off.", "1000"),
            new Setting(WebSearchTool.WEB_FIRST, Kind.choice, "Exa or Tavily first in every web search, before the Google keys, Brave and SearXNG, while its key is set and its month's limit allows. Unset or off: they answer only when a run picks them as places of their own, and the web search stays as it is.", "off", List.of("off", "exa", "tavily")),
            new Setting(CustomSearchTool.URL, Kind.address, "A search service of your own, as a place a run can pick: its address with {q} where the words go, or an address that takes q=. It is asked as it is, even on your own network; the pages its hits name are checked like any page.", "http://localhost:7700/search?q={q}"),
            new Setting(CustomSearchTool.NAME, Kind.text, "The name of that place on the brain's table, one word (custom unless set).", "intranet"),
            new Setting(CustomSearchTool.KEY, Kind.secret, "The key that search service wants, sent as Authorization: Bearer, or in the header named in custom_search_header; sent only to that address.", ""),
            new Setting(CustomSearchTool.HEADER, Kind.text, "The header the key goes in when that service does not take Authorization: Bearer.", "X-API-Key"),
            new Setting(CustomSearchTool.LIMIT, Kind.number, "The most searches the library makes through that service in a calendar month; no limit unless set.", "500"),
            // the archives and the old sources
            new Setting("RESEARCHZOSHO_TROVE_KEY", Kind.secret, "A Trove API key, for Australian newspapers in old_newspapers.", ""),
            new Setting("RESEARCHZOSHO_DIGITALNZ_KEY", Kind.secret, "A DigitalNZ key, for a higher rate on Papers Past (works without one).", ""),
            new Setting("RESEARCHZOSHO_EUROPEANA_KEY", Kind.secret, "A Europeana key, for heritage items in archive_search.", ""),
            new Setting("RESEARCHZOSHO_DPLA_KEY", Kind.secret, "A DPLA key, for heritage items in archive_search.", ""),
            // podcasts and transcription
            new Setting("RESEARCHZOSHO_CAREER_COMPANIES", Kind.number, "How many companies a career search looks at: the ones you named and kept first, then the library's proposals (60 unless set).", "60"),
            new Setting("RESEARCHZOSHO_CAREER_READINGS", Kind.number, "How many of the openings that pass the model reads in one career search, the nearest to what you want first, about a minute each (40 unless set).", "40"),
            new Setting("RESEARCHZOSHO_CAREER_CARDS", Kind.number, "How many company cards a career search files, and a night when the watch is on: each a research run of about eight minutes on the library's model, three questions, for a company whose openings pass and has no card from the last month (3 unless set; 0 turns them off).", "3"),
            new Setting("RESEARCHZOSHO_CAREER_BOARDS", Kind.text, "Portfolio job boards the career search reads for your place besides the ones it knows, each as a name and the board's address: Pillar VC=https://jobs.pillar.vc, …", "Pillar VC=https://jobs.pillar.vc"),
            new Setting("RESEARCHZOSHO_CAREER_WATCH_READINGS", Kind.number, "How many new openings the model reads each night for a person who has the career watch on (10 unless set).", "10"),
            new Setting("RESEARCHZOSHO_USAJOBS_KEY", Kind.secret, "A USAJobs API key (developer.usajobs.gov, free): federal openings in the career search.", ""),
            new Setting("RESEARCHZOSHO_USAJOBS_EMAIL", Kind.text, "The email address the USAJobs key was issued to; USAJobs asks for it with every request.", ""),
            new Setting("RESEARCHZOSHO_PODCASTINDEX_KEY", Kind.secret, "A Podcast Index API key (api.podcastindex.org/signup, free): the open podcast directory and search by person.", ""),
            new Setting("RESEARCHZOSHO_PODCASTINDEX_SECRET", Kind.secret, "The secret that came with the Podcast Index key; both are needed.", ""),
            new Setting("RESEARCHZOSHO_PODCASTINDEX_LOCAL", Kind.choice, "Keep the Podcast Index's weekly file on this machine (researchzosho podcast index download fetches it; on: the housekeeping fetches each new week's).", "off", List.of("on", "off")),
            new Setting("RESEARCHZOSHO_YAHOO_JP_CLIENT_ID", Kind.secret, "A Yahoo! JAPAN Client ID (developer.yahoo.co.jp, free): what Yahoo!ショッピング lists, with prices, for a shopping question in Japan.", ""),
            new Setting("RESEARCHZOSHO_RAKUTEN_APP_ID", Kind.secret, "A Rakuten Web Service application ID (webservice.rakuten.co.jp, free): what 楽天市場 lists, with prices; needs the access key too.", ""),
            new Setting("RESEARCHZOSHO_RAKUTEN_ACCESS_KEY", Kind.secret, "The access key that comes with the Rakuten application ID.", ""),
            new Setting("RESEARCHZOSHO_ICECAT_USER", Kind.text, "An Open Icecat account name (icecat.biz, free): a product's datasheet by brand and model, or by barcode.", ""),
            new Setting("RESEARCHZOSHO_REDDIT_CLIENT_ID", Kind.secret, "A Reddit app of your own (type script; Reddit approves new ones by ticket, and closes its public API in March 2027): what owners wrote about a product, through Reddit itself; without it the threads are read from the archive. Needs the secret too.", ""),
            new Setting("RESEARCHZOSHO_REDDIT_CLIENT_SECRET", Kind.secret, "The secret of that Reddit app.", ""),
            new Setting("RESEARCHZOSHO_REDDIT_ARCHIVE", Kind.choice, "Reddit threads read through the Arctic Shift archive, a copy of Reddit minutes to hours behind, kept by one person with no promise of uptime (on, the default). off: Reddit is pointed at and never read. Reddit's own feeds close on 2026-11-13 and its public API in March 2027.", "", List.of("on", "off")),
            new Setting("RESEARCHZOSHO_COMPANIES_HOUSE_KEY", Kind.secret, "A Companies House API key (developer.company-information.service.gov.uk, free): the UK company register read through its API for the career search; without it the register's own search page is read, twenty companies a page.", ""),
            new Setting("RESEARCHZOSHO_GBIZINFO_TOKEN", Kind.secret, "A gBizINFO API token (info.gbiz.go.jp, the API registration page, free): Japan's company information read for the career search; without it Japan has no register reader.", ""),
            new Setting("RESEARCHZOSHO_STACKEXCHANGE_KEY", Kind.secret, "A Stack Apps key (stackapps.com/apps/oauth/register, free): raises the Stack Exchange API's allowance from 300 calls a day to 10,000, for the forum_search tool and researchzosho forums on The Workplace, Software Engineering, Stack Overflow and the other sites. Without it the 300 are enough for a few dives a day.", ""),
            new Setting("RESEARCHZOSHO_EBAY_CLIENT_ID", Kind.secret, "An eBay developer keyset's client id (developer.ebay.com, free; production access by a short application): eBay listings and auctions in product_search kind=marketplaces.", ""),
            new Setting("RESEARCHZOSHO_EBAY_CLIENT_SECRET", Kind.secret, "The client secret of that keyset; both are needed.", ""),
            new Setting("RESEARCHZOSHO_ETSY_KEY", Kind.secret, "An Etsy app keystring (etsy.com/developers, a personal app): Etsy listings, the handmade and vintage tail.", ""),
            new Setting("RESEARCHZOSHO_CINII_APPID", Kind.secret, "A CiNii appid (NII's developer registration, free): CiNii Books in book_search for Japanese books by 件名 (subject heading), author and words, each with how many Japanese libraries hold it.", ""),
            new Setting("RESEARCHZOSHO_RECALLS", Kind.choice, "The recall watch: the housekeeping fetches the open product-safety feeds (US CPSC and FDA, EU Safety Gate, UK OPSS, the OECD portal) and matches them against what you own; a notice that may concern something you own waits in the inbox. on by default.", "on", List.of("on", "off")),
            new Setting("RESEARCHZOSHO_MARKET", Kind.text, "The country you buy in, as two letters (JP, US, GB, DE…): the shops, the currency, the recall notices and the reviewers a shopping run uses. Set at setup from this machine's country; the chat can switch it for one conversation.", "JP"),
            new Setting("RESEARCHZOSHO_CALIBRE", Kind.text, "Your Calibre library: its folder, or its metadata.db. Read only, never changed. What you rated there says what you like, and a book already on the shelf is not recommended again; the my_shelf tool and `researchzosho myshelf` read it.", "~/Calibre Library"),
            new Setting("RESEARCHZOSHO_DNA_MINUTES", Kind.number, "Minutes a night the housekeeping spends reading the books on your shelf for their DNA (the model labels about one book a minute; the books you went for come first; 20 unless set, 0 turns it off).", "20"),
            new Setting("RESEARCHZOSHO_DNA_SHARE", Kind.choice, "Contribute your books' DNA to the shared pool: on writes a pack of the labelled documents (the books' layers, nothing of you) to ~/.researchzosho/dna/outbox once a week for you to send. off unless set.", "off", List.of("on", "off")),
            new Setting("RESEARCHZOSHO_DNA_CONTRIBUTOR", Kind.text, "The name a contributed pack carries; anonymous when unset.", "a reader"),
            new Setting("RESEARCHZOSHO_WHISPER", Kind.address, "A transcription server (an OpenAI-style /v1/audio/transcriptions, such as speaches or faster-whisper-server); the local container when unset.", "http://gpu-box:18890"),
            new Setting("RESEARCHZOSHO_WHISPER_MODEL", Kind.text, "The transcription model on that server.", "Systran/faster-whisper-small"),
            new Setting("RESEARCHZOSHO_TRANSCRIBE_EPISODES", Kind.number, "How many podcast episodes one research run may transcribe (2 unless set).", "2"),
            new Setting("RESEARCHZOSHO_TRANSCRIBE_MINUTES", Kind.number, "How many minutes of audio one research run may transcribe (120 unless set).", "120"),
            new Setting("RESEARCHZOSHO_TRANSCRIBE_FOLLOWED_MINUTES", Kind.number, "Minutes of audio the housekeeping may transcribe per night for the podcasts you follow (0 = no cap).", "0"),
            // runs and the service
            new Setting("RESEARCHZOSHO_JOB_DRIVES", Kind.address, "The model servers the research runs use when there are several, comma-separated (OpenAI-style addresses); the drive alone when unset. Each carries one run per three of its slots, and a server with more slots takes more of the runs. How many runs go at once follows at the next restart; where a run starts follows at once.", "http://127.0.0.1:8080,http://192.168.1.20:8080"),
            new Setting("RESEARCHZOSHO_JOB_WORKERS", Kind.number, "How many research runs the service runs at once. Unset: one run per three of each model server's slots, added up over job.drives (a llama.cpp server says how many; Ollama is taken as one, vLLM as eight). Takes effect at the next restart.", "1"),
            new Setting("RESEARCHZOSHO_RESEARCH_WORKERS", Kind.number, "How many readers one research run uses at once. Unset: three, or the model server's slots when fewer. A running run follows at its next turn.", "3"),
            new Setting("RESEARCHZOSHO_WRITEUP_FETCHES", Kind.number, "How many new pages the write-up of a research run may fetch to verify something doubtful; after that it writes from the evidence the readers gathered. A page a reader already read is not counted. 6 unless set; 0 for none.", "6"),
            new Setting("RESEARCHZOSHO_STALL_MINUTES", Kind.number, "After how many minutes without progress a run is marked as stalled (15 unless set).", "15"),
            new Setting("RESEARCHZOSHO_SUBMIT_CHECK_SECONDS", Kind.number, "How long a program's submit waits for the model's reading of a question before filing anyway (45 unless set).", "45"),
            new Setting("RESEARCHZOSHO_UPDATE", Kind.choice, "Updates: check and say, update by itself, or neither.", "check", List.of("check", "auto", "off")),
            new Setting(Oidc.ISSUER, Kind.address, "Sign-in through your identity provider (Google Workspace, Entra, Okta, Dex): the provider's issuer address, as its discovery document names it. Unset: no such sign-in.", "https://accounts.google.com"),
            new Setting(Oidc.CLIENT_ID, Kind.text, "The client id the provider gave this library.", ""),
            new Setting(Oidc.CLIENT_SECRET, Kind.secret, "The client secret, when the provider gave one.", ""),
            new Setting(Oidc.ALLOWED_DOMAIN, Kind.text, "Mail domains whose people may sign in for the first time, as readers (example.org, or several with commas). Unset: only people already listed here.", "example.org"),
            new Setting(Oidc.NAME, Kind.text, "What the sign-in button says. Unset: the provider's host.", "Example Workspace"),
            new Setting(Proxy.SETTING, Kind.text, "The proxy or load balancer in front of the library, as addresses or networks (10.0.0.0/8, 127.0.0.1): only a request from one of them has its forwarded headers and a relay's identity headers believed, and the sign-in cookie is Secure when it says the person came over https. Unset: no proxy is believed.", "10.0.0.0/8"),
            new Setting(Accounts.SESSION_DAYS, Kind.number, "How many days a browser stays signed in. Unset: 365.", "30"),
            new Setting(Logs.SETTING, Kind.choice, "Where the service writes its log: stderr at a terminal, stdout where no terminal is attached (a container, a cloud runner). Unset: by the terminal.", "", List.of("stdout", "stderr")),
            new Setting("RESEARCHZOSHO_WEB_SIGNIN", Kind.choice, "Whether the pages ask for a sign-in. Off as shipped: anyone who can open them is the person who keeps the library. researchzosho share turns it on.", "off", List.of("on", "off")),
            new Setting("RESEARCHZOSHO_WEB_LOCAL", Kind.choice, "With the sign-in on, a browser on the library's own computer is the owner without signing in (owner), or signs in like any other (signin), for a computer other people use.", "owner", List.of("owner", "signin")),
            new Setting("RESEARCHZOSHO_SHARE", Kind.choice, "Reaching the library from outside your home: off, on your tailnet, public through Tailscale Funnel, or a Cloudflare quick tunnel. researchzosho share sets it; the service follows within half a minute.", "off", List.of("off", "tailscale", "funnel", "cloudflare")),
            new Setting("RESEARCHZOSHO_SHARE_ADDRESS", Kind.address, "Where the library is reached from outside, as the relay reported it. Join links are written with it. A quick tunnel's address changes at every start.", "https://mylibrary.tail1234.ts.net"),
            new Setting("RESEARCHZOSHO_SHARE_MAIL", Kind.text, "The quick tunnel's mail list: only these addresses (or *@a-domain) get through, with a one-time code from Cloudflare. Empty: anyone with the address reaches the sign-in page.", "ann@example.com,*@example.org"),
            new Setting("RESEARCHZOSHO_DOOR", Kind.number, "The shared door's port on this computer, which only the relay points at.", "4651"),
            new Setting("RESEARCHZOSHO_RECORDS", Kind.choice, "The record sources (registers, newspapers, censuses) in every run, or only when a field asks.", "field", List.of("always", "field", "off")),
            new Setting("RESEARCHZOSHO_FETCH_PRIVATE", Kind.choice, "Whether a run may fetch addresses on your own network. Unset, a library at home may and a library behind a trusted proxy may not.", "allow", List.of("allow", "deny")),
            new Setting("RESEARCHZOSHO_FETCH_MAX_BYTES", Kind.number, "The largest document a run downloads, in bytes.", "20000000"),
            new Setting("RESEARCHZOSHO_FETCH_MIN_BYTES_PER_SECOND", Kind.number, "The slowest a page may come before the fetch is given up.", "2000"),
            new Setting("RESEARCHZOSHO_EXPLORER_PER_NIGHT", Kind.number, "How many open questions the housekeeping researches per night.", "2"),
            new Setting("RESEARCHZOSHO_EXPLORER_TYPES", Kind.text, "Which kinds of open question the housekeeping takes.", ""));

    public static List<Setting> known() { return KNOWN; }

    /** The setting a name means: the key, the key without its prefix, upper or lower case, dots or underscores; null when there is none. */
    public static Setting named(String name) {
        if (name == null) return null;
        String n = name.strip().replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
        if (!n.startsWith("RESEARCHZOSHO_")) n = "RESEARCHZOSHO_" + n;
        for (Setting s : KNOWN) if (s.key().equals(n)) return s;
        return McpServers.keySetting(n);   // a key one of the keeper's MCP servers wants: a secret setting of its own
    }

    /** A value as it may be shown: a secret only as set or not, and its last three characters. */
    public static String shown(Setting s, String value) {
        if (value == null || value.isBlank()) return "";
        if (s.kind() != Kind.secret) return value;
        return "set (ends with …" + value.substring(Math.max(0, value.length() - 3)) + ")";
    }

    /** Why a value cannot be this setting's, in a sentence; "" when it can. */
    public static String refuse(Setting s, String value) {
        if (value == null || value.isBlank()) return "";
        String v = value.strip();
        return switch (s.kind()) {
            case address -> {
                boolean several = s.key().equals(JOB_DRIVES);
                for (String part : several ? v.split("\\s*,\\s*") : new String[]{v})
                    if (!part.matches("^https?://\\S+$")) yield s.shortName() + " is an address" + (several ? ", or several with commas between," : "") + " and must start with http:// or https://, for example " + s.example() + ".";
                yield "";
            }
            case number -> v.matches("^\\d+$") ? "" : s.shortName() + " is a number, for example " + s.example() + ".";
            case choice -> s.choices().contains(v.toLowerCase(Locale.ROOT)) ? "" : s.shortName() + " is one of " + String.join(", ", s.choices()) + ".";
            default -> s.key().equals(ReportOptions.LENGTH_SETTING) ? (ReportOptions.length(v) == null ? s.shortName() + " is short, medium, long or a number of words, for example 800." : "")
                     : s.key().equals(MARKET) ? (v.matches("(?i)^[a-z]{2}$") ? "" : "market is a country as two letters, for example JP or US.")
                     : v.contains("\n") ? s.shortName() + " is one line."
                     : s.key().equals(CustomSearchTool.NAME) && Places.builtIn(v.replaceAll("[\\s_]+", "-")) ? s.shortName() + " is the name of a place on the table, and " + v + " is already one of the library's own places: choose another word." : "";
        };
    }

    public static final String MARKET = "RESEARCHZOSHO_MARKET";
    public static final String JOB_DRIVES = "RESEARCHZOSHO_JOB_DRIVES";
    public static final String CALIBRE = "RESEARCHZOSHO_CALIBRE";

    /** The country the person buys in, as two upper-case letters: the setting, else this machine's country, else US. */
    public static String market() {
        String m = Config.get(MARKET);
        if (m != null && m.strip().matches("(?i)^[a-z]{2}$")) return m.strip().toUpperCase(Locale.ROOT);
        String c = Locale.getDefault().getCountry();
        return c != null && c.matches("^[A-Z]{2}$") ? c : "US";
    }

    /** Every setting with its value as it may be shown, in the order above. */
    public static ArrayNode list(ObjectMapper m) {
        ArrayNode out = m.createArrayNode();
        for (Setting s : KNOWN) {
            String v = Config.get(s.key());
            ObjectNode o = out.addObject();
            o.put("key", s.key()); o.put("name", s.shortName()); o.put("kind", s.kind().name()); o.put("what", s.what());
            o.put("set", v != null && !v.isBlank());
            o.put("value", shown(s, v));
            if (!s.example().isEmpty()) o.put("example", s.example());
            if (!s.choices().isEmpty()) { ArrayNode c = o.putArray("choices"); for (String x : s.choices()) c.add(x); }
        }
        // the keys MCP servers want: the presets' always, so they can be set here; the keeper's own servers' once one is kept
        List<String> more = new ArrayList<>();
        for (McpServers.Preset p : McpServers.presets()) more.add(p.keySetting());
        more.addAll(Config.storedWithPrefix(McpServers.KEY_PREFIX).keySet());
        for (String key : more) {
            if (KNOWN.stream().anyMatch(k -> k.key().equals(key)) || seen(out, key)) continue;
            Setting s = McpServers.keySetting(key);
            if (s == null) continue;
            String v = Config.get(key);
            ObjectNode o = out.addObject();
            o.put("key", s.key()); o.put("name", s.shortName()); o.put("kind", s.kind().name()); o.put("what", s.what());
            o.put("set", v != null && !v.isBlank()); o.put("value", shown(s, v));
        }
        return out;
    }

    private static boolean seen(ArrayNode rows, String key) { for (var r : rows) if (r.path("key").asText().equals(key)) return true; return false; }

    /** Writes the value (a blank value unsets it); the value must pass {@link #refuse}. */
    public static void set(Setting s, String value) throws IOException {
        String v = value == null ? "" : value.strip();
        if (s.kind() == Kind.choice) v = v.toLowerCase(Locale.ROOT);
        if (s.key().equals(MARKET)) v = v.toUpperCase(Locale.ROOT);
        if (s.key().equals(ReportOptions.LENGTH_SETTING) && ReportOptions.length(v) != null) v = ReportOptions.length(v);
        Config.set(s.key(), v);
    }
}
