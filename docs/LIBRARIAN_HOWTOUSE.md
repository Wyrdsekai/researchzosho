> **The command is `researchzosho`, or `zosho` for short.** Inside a CodeZaiku install, `codezaiku librarian`
> is the same command. In this source tree it is `bin/researchzosho`.

# Using ResearchZosho

This document explains how to use ResearchZosho. It covers setup, sending a question, reading and
reviewing what comes back, and running it as a service. It is written for people who are not
programmers. The protocol for programs is in [LIBRARY_PROTOCOL.md](LIBRARY_PROTOCOL.md).

## Contents

1. What you need
2. Setup
3. The basic loop
4. Sending a question
5. Reading the write-up
6. Reviewing the claims
7. Asking what the library already has
8. Adding your own documents
8b. Translating a document
8c. Pictures
9. Languages
10. Checks against bad sources
10b. Archives, old newspapers, Japanese books, code, and other video sites
10c. Podcasts
10d. Settings
10e. What you own
10f. Shopping
10g. Books
11. The housekeeping
12. Sharing the model
12b. Talking to the Librarian
12c. Bridges: questions that connect two subjects
12d. Your databases
13. The map
14. Fields
15. Running it as a service
16. The web pages
17. Obsidian and SoloMD
18. Other programs
19. Change notifications
20. Other libraries
21. Access requests
22. Directories
23. Seeks: separate pulls and one merge
24. Law and patents
25. People and organizations
26. Suggestions
26b. Looking for work
27. Not yet available

## 1. What you need

- Java 21 or newer, or none: each release also carries builds with their own Java runtime for Linux,
  macOS and Windows (x64 and arm64). The install one-liners pick one when the machine has no Java 21.
  `RESEARCHZOSHO_RUNTIME=1` asks for it outright.
- A model server that speaks the OpenAI chat API. This can be a local server (llama.cpp, Ollama, LM
  Studio) or a hosted API with a key (OpenAI, DeepSeek, Gemini, OpenRouter and any other that speaks
  the OpenAI chat API), or Claude through Anthropic's own API. Setup asks for the address and, for a
  hosted API, the key. Without a model you can still ask what the library has and read it. Research
  runs need the model. Hosted models are described below.
  `researchzosho models` reads your card's memory and prints the best choice for it, with the
  command that serves it. Setup prints the same when it finds no model server.
  Size matters. On the same five questions, a 27B-class model and a 9B both get the facts right.
  But the 9B's write-ups yield fewer claims (10 against 25) and almost no citation the checker can
  read (1 against 26). The steps that judge a write-up ask for structured answers, and a small model
  mangles them. A 27B-class model, local or hosted, does the whole job.
  On a 16 GB card a 27B at 4-bit does not fit. Three choices do well there on the same questions:
  - gpt-oss-20b: right, eight claims, about five minutes a question, 13 GB in use with two
    16,000-token slots.
  - Gemma 4 26B-A4B: right and the most careful writer, ten claims, about nine times slower.
  - Gemma 4 12B: right, ten claims, six times slower, 9 GB in use.

  Not recommended on 16 GB: Qwen3 14B and Mistral Small 24B got a fact wrong, and Mistral needs
  19 GB with a working context. The 27B at 3-bit was careful but slow and missed twice.
  Phi-4-reasoning-plus and the 8B and 9B models copied the prompt's own words or answered from
  memory without sources.
  Smaller cards, with one 16,000-token slot:
  - 8 GB: Qwen3.5 9B (right and deep, ten claims, 5.9 GB in use), or Gemma 4 12B's smaller 4-bit
    file (right, 7.3 GB in use, slower).
  - 4 GB: Gemma 4 E4B (right, nine claims, the best citation reader of the small models).
  - 2 GB: Gemma 4 E2B got the claims right but wrote empty sections. A hosted API is the better
    answer at this size.

  Not recommended on small cards: Qwen3 8B and Llama 3.1 8B (thin), Granite 4.1 8B (needs 9.4 GB),
  Falcon-H1 7B and SmolLM3 3B (answered without fetching a source), Nemotron 3 Nano 4B and Granite
  4.0 micro (mixed up the lead limits). Each model answered each question once.
  Give the model server a context of 16,000 tokens or more per request. A smaller one leaves the
  review too little room.
- A web search backend, for research runs that go to the web. This matters as much as the model: a
  run can only read what a search finds. The choices, best first:
  - a Brave Search API key (https://brave.com/search/api/ has a free plan);
  - a SearXNG instance (free, private; setup can start one with Docker);
  - the built-in fallback, which searches only Wikipedia and the scholarly literature.

  Section 2 explains.
- Optional: an embeddings server, if you want search by meaning as well as by keywords. `researchzosho
  model install` sets one up beside the model, at the same address, on all three platforms. On Linux
  with an NVIDIA card it is Text Embeddings Inference serving Qwen3-Embedding-0.6B; with an AMD or
  Intel graphics chip it is llama.cpp's container serving the same model on the same chip. On a Mac or
  Windows it is llama.cpp's own build. The model downloads once, about 1.2 GB (0.6 GB for llama.cpp).
  Without a GPU on Linux there is none. `researchzosho embed start` on its own does the same: Text Embeddings
  Inference on an NVIDIA card, llama.cpp's container on an AMD or Intel chip. `researchzosho embed start --cpu` runs the CPU image at about a tenth of a chunk a
  second, so a thousand documents take more than a day. An embeddings server on another machine is
  the better answer. Any server that answers the OpenAI embeddings call works. The server matters more
  than the model: the same 0.6B model does 13 chunks a second under llama.cpp and 115 under Text
  Embeddings Inference on the same card. A library of a thousand documents re-indexes in two minutes
  instead of twenty.

### Hosted models

Which hosted model is worth it is measured: [MODELS.md](MODELS.md) has the picks and the table, after the
local list. A hosted model needs an address and a key, which setup asks for, or which you set with
`researchzosho settings set drive <address>` and `settings set api_key <key>`, and `settings set model
<name>`. What to know for each:

- **Anthropic (Claude).** Address `https://api.anthropic.com` (setup takes the word `anthropic`), a key
  from platform.claude.com, a model such as `claude-sonnet-5-5`, `claude-haiku-5-5` or `claude-opus-5-5`.
  The library uses Anthropic's own API, with prompt caching: a research run sends the whole
  conversation with every call, and most of it is read back from the cache at a twentieth of the price.
  A run costs about $1.50 on Sonnet, about $3 on Opus and about ten cents on Haiku. The setting
  `anthropic_effort` (low, medium, high, xhigh, max) chooses how hard the model thinks; unset is the
  model's own default. Rate limits are built in: the library waits as long as Anthropic asks and
  watches the minute's token allowance.
- **OpenRouter.** Address `https://openrouter.ai/api/v1` (setup takes the word `openrouter`), a key from
  openrouter.ai, and a model id from openrouter.ai/models that supports tool calling, for example
  `deepseek/deepseek-v4.1-flash` or `z-ai/glm-5.3-flash`, which cost cents a run, or
  `anthropic/claude-sonnet-5.5` at Anthropic's own price. The library asks OpenRouter for a provider
  that takes tool calls, waits out a rate limit as the answer asks, and reads an error a provider
  puts inside a successful answer. Free models are limited to 50 requests a day, or 1,000 once you
  have bought $10 of credit; a research run is about 110 requests.
- **OpenAI.** Address `https://api.openai.com` (setup takes the word `openai`), a key from
  platform.openai.com, a model such as `gpt-6.1-sol` (about $2 a run) or `gpt-6-luna` (about 10 cents).
  The library uses OpenAI's Responses API, which its current models need for tool calls: their
  reasoning is carried from one call to the next. Caching is automatic. The setting `openai_effort`
  (none to max; each model takes some of these) chooses how hard the model reasons; unset is the
  model's own default. The models list does not say how long a model's context is, so the library
  takes 272,000 tokens, the standard-price tier; `RESEARCHZOSHO_OPENAI_CONTEXT` sets another.
- **Gemini.** Address `https://generativelanguage.googleapis.com/v1beta/openai/`, a key from AI Studio,
  a model such as `gemini-3.8-flash`. Google calls this endpoint a beta.
- **Groq.** Fast, but its plans allow 8,000 tokens a minute and 200,000 a day at the start, and one
  research call averages 16,000 tokens, so it suits the chat and short asks, not research runs.

- **Nebius Token Factory.** Address `https://api.tokenfactory.nebius.com/v1` (setup takes the word `nebius`), a key
  from tokenfactory.nebius.com, open models such as `deepseek-ai/DeepSeek-V4.1-Flash` or `zai-org/GLM-5.3-Flash`, hosted
  in the EU. Its limits start around 60 requests and 400,000 tokens a minute and grow as you use them; no prompt
  caching, so a run costs its whole token count.
- **Hugging Face Inference Providers.** Address `https://router.huggingface.co/v1` (setup takes the word `huggingface`),
  a fine-grained token with the "Make calls to Inference Providers" permission, and a model id such as
  `deepseek-ai/DeepSeek-V4.1-Flash`, with `:cheapest`, `:fastest` or a provider's name on the end to choose among the
  fifteen providers behind it. Billed at the provider's price with no markup.

### Best judgement: `auto`

With several models named, `--model auto` (or `/model auto` in the chat, or `auto` in the research form) picks one for the job and says
why: `auto chose deepseek: tier A for a tier-A research job, about 10 minutes, about $0.13; fable about $2.50, dearer or slower`. The rule:
among the named models that have a key and are under their cap, those below the tier the job needs are dropped; when somebody is
waiting, a quick ask or a chat turn, those slower than the wait you accept are dropped; the cheapest of the rest is taken, so a free local
model wins whenever it clears the tier and the wait. A research run needs tier A, a chat turn B. Tiers come from what the models did on
the scouting question, or you set one: `researchzosho models set mimo --tier B`. Once a second model is named, auto is the default when
none is named; `researchzosho settings set auto off` restores the old behaviour. `auto.wait_minutes` (5), `auto.max_dollars` and
`auto.local_only` are the knobs; `researchzosho models auto` shows the pick without running anything.

### Named models, chosen per run

A library can keep several models by name and run each question on the one you pick:

```
researchzosho models add deepseek openrouter deepseek/deepseek-v4.1-flash --prices 0.032/0.144/0.002
researchzosho models add fable anthropic claude-fable-5-1 --prices 10/50/0.25 --cap 50
researchzosho models key openrouter <key>        # or --file ~/openrouterapi.txt
researchzosho models                             # lists them; models test <name> asks the host
researchzosho research ask "…" --model deepseek
```

The same choice is the dropdown on the web page's research form, `/model deepseek` in the chat (for the rest of that
session; `/model` alone lists them), and `model` on the MCP research tool. A key is kept once per host and goes to that
host only. The library's own drive and model are the entry called `default`.

Each report says under its question who researched it and what it cost at the entry's prices, from the tokens the server
counted. `researchzosho spend` adds the runs up by host and by model, this month and in all. An entry with `--cap <dollars>`
refuses a run that would start past that figure in the month, and says so.

The log's `usage ←` lines say what each call cost in tokens and how much the cache served;
`researchzosho stats` adds them up for a run. Every hosted model sees what the research reads, so
`db add` and the family's own texts warn you that rows and texts will be sent there.

## 2. Setup

Run:

```
researchzosho setup
```

Setup asks a few questions. Each has a default answer. Press Enter to accept it.

1. Where the library folder goes, and what the library is called. The name is what the pages and
   programs show. `researchzosho name <a name>` changes it later. It is the `name:` line of
   `catalog/library.md`.
2. Which model server to use. Setup looks on the usual local ports. It tests the server with one
   call before saving the address.
3. Which web search backend to use. Setup asks for a Brave Search API key first. Then it looks for
   SearXNG on its usual local port. If Docker is installed, it offers to start one. With neither, it
   says that the built-in fallback will be used.
4. Whether search works by meaning or by words only. If your model server also embeds, that is used.
   The one `model install` sets up does. Otherwise, with Docker and a GPU (NVIDIA, AMD or Intel), setup offers to
   start an embeddings server. `researchzosho embed start` does the same later. Without those it asks
   for an address. With none, search is by words.
5. Whether to run ResearchZosho as a service that starts when you log in.
6. Which programs to connect. If Claude Code, Codex or Gemini CLI is installed, setup can register
   the library with it. For any other program that speaks MCP, setup prints the command line and the
   JSON to enter.

Then setup adds one document you name and answers a question about it. Run setup again at any time
to change one of these answers.

### Web search

Research runs find their sources through a search backend. A run can only read what a search
finds, so this choice matters as much as the model. ResearchZosho tries them in this order:

1. **Google, through a key you pay for.** A Serper key (https://serper.dev, `serper_key`) or a SerpApi key
   (https://serpapi.com, `serpapi_key`) gives Google's own results. Both are paid per search after their free
   allowance, Serper about a tenth of a cent a search and SerpApi about one and a half cents, and a research
   run makes tens of searches, so set one only when you mean to pay for it. Since the end of October 2026
   Google releases only the top three links of a search at once; through Serper the library asks for the rest
   with a free second call, and an address still unreleased is shown as a Google redirect with the site's
   readable name, for the run to search for by that name. Through SerpApi most addresses come as such
   redirects today, so Serper is the better key. Each key is sent only to its own service.
2. **The Brave Search API.** Good results, and a free plan. Get a key from https://brave.com/search/api/.
   Put it in `RESEARCHZOSHO_BRAVE_KEY`, or give it to setup. The key is sent only to Brave.
3. **SearXNG**, a search engine you run yourself. Free, and your queries stay on your machine.
   Setup starts it with Docker when Docker is installed. So does:

   ```
   researchzosho search start
   ```

   This starts the official container on port 8888 with a settings file ResearchZosho writes. The
   file turns on the JSON format the search tool needs. It picks the engines that answer from a home
   machine: Seznam, Naver, Yandex, Yahoo and Wikipedia. SearXNG's own default engines (DuckDuckGo,
   Google, Qwant, Startpage, Brave) answer the first query with a CAPTCHA and stay suspended, so they
   are off. The file is `~/.researchzosho/searxng/settings.yml`. You can edit it. A SearXNG elsewhere
   goes in `RESEARCHZOSHO_SEARXNG`.

**What a month of searching cost.** The library counts every search and every page read through a service,
by month and by service, with the credits the paid ones charge. `researchzosho search status` says, for
instance, "This month: 123 searches through Serper, 123 credits; 40 through Brave; 12 pages through
Firecrawl, 12 credits." The tally is `search-tally.json` in the library's home. Each of the paid keys can have a
monthly limit, none unless you set one: `serper_monthly_limit`, `serpapi_monthly_limit`, `brave_monthly_limit` for a
paid plan and `firecrawl_monthly_limit`. At the limit the library passes that service over until the month turns and
says so, and the Keep page shows the month's use beside the limit.

**Pages a plain fetch cannot read.** Some pages are drawn by scripts in the browser and come to a program
as an empty shell; some sit behind a bot wall or answer 403 to anything but a browser. With a Firecrawl key
(https://www.firecrawl.dev, `firecrawl_key`) the run reads such a page through Firecrawl's browser instead,
and says so above the text. It is paid per page after the free credits, about a tenth of a cent each, and a
run reads at most twenty pages this way; a page whose site's terms forbid machine reading is not read that
way either. Without the key the run looks for an archived copy, as before.
4. **The built-in fallback**, when neither is set. It uses Wikipedia's own search, in the language of
   the query, then papers from Crossref and OpenAlex. No key and no install, and all three answer
   reliably. But it finds encyclopedia pages and the literature, not the whole web. A run follows
   the pages' references for primary sources. `RESEARCHZOSHO_FALLBACK_SEARCH=off` turns it off.

With a key and a SearXNG both set, Brave is used and SearXNG is the fallback. The report's "Web
search" section says when a run went through the fallback, or when no backend answered.

Every research run also has `scholar_search`, separate from all three: Crossref and OpenAlex,
papers and books by DOI. It is in every configuration. A web engine ranks the primary literature
low or not at all, and a DOI is a source the library can resolve and check.

Every run also has the archives and the old sources, with no key and no setup: the web archives,
the Internet Archive's texts and television news, digitised historical newspapers, the National
Diet Library's digitised Japanese books, the archives of source code, and the video sites beside
YouTube. Section 10b says what each does.

A run can also look in a few more places when the question calls for them. PubMed finds medical and
life-science articles with their abstracts; when PubMed Central has an article's full text, the run reads
all of it, section by section, and when the publisher keeps the text from programs, the run gets the
abstract and the address where you can read the article yourself. Semantic Scholar finds papers in every
field and says how often each is cited and where a free PDF is. Both are free; a free key from NCBI or
Semantic Scholar makes them faster (`ncbi_key`, `s2_key`). Exa searches the web by meaning, and Tavily
gives the passages of each page that answer the query. Both need your own key (`exa_key`, `tavily_key`),
and the library stops at a number of searches a month for each, so it never spends more than you meant:
714 for Exa, which keeps it under $5 at its price on 2026-11-04, and 1,000 for Tavily, its free allowance
(`exa_monthly_limit`, `tavily_monthly_limit`). At that number the place is left off until the month turns;
the Keep page shows the month's searches beside it, and the dollars for Exa. A run uses Exa or Tavily only
when it picks them, unless you put one first in every web search with `web_first`. You can also point the
library at a search service of your own, at home or anywhere: its address with `{q}` where the words go
(`custom_search_url`), a name for it (`custom_search_name`), and a key if it wants one (`custom_search_key`,
sent as a bearer token or in the header named in `custom_search_header`). The library reads the answer when
it is a list of hits, each with an address, a title and some text, or an object whose `results` or `items`
hold them, and says so plainly when it is not. The pages the hits name are checked like any other page.

```
researchzosho search                 # which backend answers, and the container's state
researchzosho search test <query>    # one search, and which backend answered it
researchzosho search papers <query>  # the literature by DOI
researchzosho search in <place> <query>  # one search in one place, as a run makes it: pubmed, semantic-scholar, exa…
researchzosho search open <address>  # an address read as a run reads it: a PubMed Central article as its full text
researchzosho search stop            # stop the container
```

### Search by meaning

Search by words finds a claim by the words in it. Search by meaning also finds it when the question
uses other words, or another language. It needs an embeddings server. The setting is
`RESEARCHZOSHO_EMBED`: the address of any server that answers the OpenAI embeddings call, or `off`.
`researchzosho embed status` says what is configured. `embed start` runs one with Docker. `embed test`
measures it. `embed stop` stops it. `researchzosho status` says in one line whether search by meaning is on — and
says so when a server is configured but not answering, since searches are by words only until it is. After starting one, run `researchzosho rebuild` to index the
library with it. The nightly housekeeping would do that the next night.

### Updating

`researchzosho status` and the page footer say when a newer release exists. To install it:

```
researchzosho update            # the installed release, the versions kept, the latest, and the mode
researchzosho update now        # download, verify against the release's checksums, install beside the running one, restart the service
researchzosho update back       # the version before runs again, and the service restarts onto it
researchzosho update auto on    # the service installs a newer release after each housekeeping and restarts at once
```

The setting is `RESEARCHZOSHO_UPDATE`: `check` (default), `auto`, or `off`. An update never touches
the library or the settings. Each version is installed in a folder of its own, `versions/<version>` in the
install, and the launchers run the one `current` names (`current.txt` on Windows), so the running program's
files are left alone. The version before stays as the way back, and two are kept. The service restarts at
once, and a job that was running resumes where it was when it is back (section 4). After `update back` the
auto-update leaves the version you went back from, and `update now` installs it again. An install from before
0.7.2 moves into this layout at its first update. On Windows a small helper starts the service again once the
old one has ended, and moves an install from before 0.7.2 then; what it did is written to
`~/.researchzosho/logs/update.log`.

One update runs at a time. An update that starts while another is running, from this program or
another one, changes nothing and says so. On Windows that holds until the new version is in place.

#### For a program that keeps ResearchZosho up to date

CodeZaiku, Wyrdsekai and any other program that updates ResearchZosho ask its own updater. They
never download it or replace its files themselves:

```
researchzosho update --json        # installed, running, latest, newer, mode, root, canUpdate, updating, versions, held
researchzosho update now --json    # result, code, from, to, finishesAfterExit, note
researchzosho update back --json   # the same fields
```

`update now` ends with 0 when it updated or the install was already current, 75 when another update
is running (ask again later), 3 when this install cannot update itself (a run from the source tree),
and 1 when it failed. With `--json` it prints one JSON document, and the progress goes to the error
stream. `installed` is the version whose files are in place, and `running` the one answering, which
differ until a program started before the update is started again. `versions` lists the versions kept, and
`held` names the one `update back` went back from. Only ResearchZosho's own service updates it without being
asked, and only with `RESEARCHZOSHO_UPDATE=auto`. While the service restarts after an update, calls to it fail
for a few seconds: try them again. A run a program started goes on after the restart, under the same id.

## 3. The basic loop

1. Send a question. Use the web pages or `researchzosho research ask "…"`. If the question is
   rough, run `researchzosho sharpen` first (section 4).
2. Read the write-up when the run is done. The pages show it with its sources. `researchzosho
   explain` gives a simpler version (section 5).
3. Review the claims. They arrive in your inbox. Accept or dispute each one (section 6).
4. Use what you kept. `researchzosho ask` answers from it. The map shows how it connects. The vault
   has it in your notes.
5. Check in now and then: open questions, proposed subjects, and claims whose source changed.

Steps 2 to 4 happen as soon as a run is done. The housekeeping (section 11) runs every night. It
does the checks and cleanup.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="brand/loop-dark.png">
  <img alt="The loop: you ask a question. It reads and takes notes. It checks each claim against its source and writes the report. The report arrives and its claims go on the shelves under your subjects. You decide: keep or dispute each claim. You use what you kept: ask, the map, explain. Then the next question. Side steps: open questions it could not answer, to ask again; new subjects when none of yours fit, to accept or drop; and a claim comes back to you when its source changed." src="brand/loop.png" width="860">
</picture>

The drawing's source is `brand/loop.dot`. `dot -Tpng` redraws it.

## 4. Sending a question

### The commands

```
researchzosho research ask "what do the trials since 2020 say about statins for someone my age"
researchzosho research ask "…" --depth          # go deep on the best sources instead of surveying
researchzosho research ask "…" --quick          # a short run: front of the line, 12 model steps, 6 minutes
researchzosho research ask "…" --priority       # a full run, ahead of the others waiting
researchzosho research ask "…" --title "Statins after sixty"   # the report's title; without it, the question is the title
researchzosho research ask "…" --shelves        # read only your own documents
researchzosho research ask "…" --max-turns 200  # limit the run to 200 model steps
researchzosho research ask "…" --max-minutes 120
researchzosho research ask "…" --no-paid        # the free search places only: nothing through Exa, Tavily, Serper, SerpApi, Brave or Firecrawl
researchzosho research ask "…" --genealogy      # research it in genealogy mode: records, your family tree, your relatives
researchzosho research ask "…" --youtube        # the YouTube field: channels, videos, what is said and when (needs `researchzosho video install`)
```

From Claude Code or a program, the same thing is `library_research`. From the web pages, use the
Research page; its "What it is for" names the goal, as `--goal` does on the command line, and left blank the
library reads the goal off the question.

**How long, how the sources are written, and what shape.** A report can be short (about 400 words), medium
(about 1,200), long (about 3,000) or a number of words. Its sources can be numbered, the default, or written in
APA, MLA, Chicago or IEEE style. It can also be a reading list, each source with what it is, why it matters for
your question, how strong it is and its link, or an outline, headings with one line each. Say it in the chat
("a short report", "give me a reading list on …", "just an outline", "use APA citations"), choose it on the
Research page, or add it to the command: `--length short --citations apa --shape reading-list`. The settings
`report_length` and `citation_style` are the library's defaults. A question that asks for a format in its own
words gets that format. Whatever you choose, the report keeps its checks and its sources, and the numbers in the
text stay, so every cited sentence is still checked against its source.

**Genealogy mode is used only when you ask for it.** A question such as "who were my great-grandfather's
parents" looks like family history. The library then says so once, in the place where you asked:

- In a terminal, it asks: `This looks like family history. Genealogy mode searches record collections
  (registers, newspapers, censuses), builds your family tree and uses what the library already knows
  about your relatives. Use genealogy mode for this question? (y/N)`. Type `y` or `yes` for genealogy
  mode. Enter, or anything else, sends the question as ordinary research.
- In a script or a pipe, nothing is asked and nothing waits. The question goes as ordinary research, and
  the command prints the command that asks for genealogy mode, ready to paste.
- On the Research page, the box "Family history (genealogy mode)" is unticked. The page says beside it when
  a question looks like family history. Sent as it is, the question is ordinary research.
- A program that uses `library_research` gets `suggestion` in the result, and asks for genealogy mode with
  `field: "genealogy"`.

The same question is told once. `--field <name>` asks for any field by its name.

A question about YouTube — which channels make a kind of video, whether one still posts, what is said in
a video — gets the `youtube` field by itself from its words, once the video helper is installed
(`researchzosho video install`). A video you watched somewhere and cannot name is found by saying what you remember in
the chat. The run does not search YouTube for it, and has no ranked search on its table to do so: YouTube's own search ranks
by popularity, and a video you remember is seldom popular. The model names the thing the video makes or shows, in each language it may be in, and passes on what
you remember of the video itself, what happens in it, what is seen or heard, in short phrases of your own words. The library names
the thing plainly in the other languages YouTube is large in, and gathers the candidates itself: deep searches of its
own plain phrasings of every name, a hundred results each; every upload of the
channels those surface; YouTube's Data API in date order when you set a key; the other search indexes; and your own
watch history first, when you have added it. A title seldom says what happens in a video, and other people's pages often
do, so when a web search index is set up the library also makes its own web searches from the thing's name and what you
remember, takes the videos among the results, and reads the pages for the videos they link or embed; a video found this
way says which site led to it. It drops what the dates, the length and the language of the title rule out
and the videos you have already said no to, and lists the rest best fit first: the videos that hold more of what you
remember, in their own title or description or beside their link on a page, before those that hold less, with no
popularity in it: no views, no subscribers, no place in an engine's results, and no count of how many sources found a
video, since what every engine finds is what is popular everywhere.
A pool holds thousands and a look costs minutes a video, so the run sifts the pool next: it fetches each candidate's
thumbnail, YouTube's cover picture, and has the model that reads pictures say yes, maybe or no for what one picture can
show, the thing itself, its colours, a tag; then, for those that pass, the first storyboard sheet, the first
minutes of the video as small frames, for what you remember about the start. A thumbnail takes a second or two and a
sheet about ten on a local model, so the run has ninety minutes by default when you set none (`--max-minutes` sets
another), the sift stops cleanly when its time is up and says how many it did not reach, and nothing is judged twice.
Then the run looks at the first five survivors together, sparse, each for everything you remember, then the next five,
down the survivors while the time lasts, and answers with the one where the most was found, the second each remembered
thing was seen at, and how each was checked; a thing not found or not looked at is said first. When the model that reads pictures stops answering, a look stops after two frames go
unanswered, says so, and keeps nothing half read, so the video is looked at again later. Something you saw in a video a
while ago is found again on the Video page, by what was said or by what is on screen. [YOUTUBE.md](YOUTUBE.md) has the
setup, the commands for looking things up yourself, the candidate search, the watch history, and the rules a run keeps
in that field.

The run starts as soon as a worker is free. `researchzosho jobs` lists the runs, queued and running
ones first. `researchzosho jobs <J-id>` shows one run: its state, how far it has got, what it is
waiting for, and where its write-up went. A job keeps what it gets as it goes: every search, page, model answer
and worker's notes of a research run, every unit of a translation, every task of the nightly housekeeping. When the
service restarts, for an update or anything else, a job that was running resumes where it was: a research run at
the turn it was on, a translation at the unit it was on, the housekeeping at its next task. The job's line says so:
"Resumed after a restart, at turn 14." A career search, a home search and a video search begin again. A run that waits for one of its named choices is answered with
`researchzosho jobs <J-id> --choose <number or name>`; one held for the question whether to research it at all, with
`--yes` or `--no`. A run that has shown no progress for a quarter of an hour
(`RESEARCHZOSHO_STALL_MINUTES`) is marked "no progress since" with the time, and the service writes
where each of the run's threads waits into its log, once, so that whoever looks into it sees what the
run waits for.

### Sharpening a question first

```
researchzosho sharpen "how were the gears cut"
```

`sharpen` does not run any research. It returns:

- the question rewritten to be more specific;
- a list of what it assumed to get there, so you can see where it narrowed the question;
- a plan: what is in scope, what is out, sub-questions, the kinds of sources to look for, what a
  good answer must contain, and which languages to read in;
- what the library already has on the subject;
- up to three questions back to you, when an answer would change the plan;
- a suggested mode (broad or depth) and size (quick or full).

It writes the sharpened question to `sharpened-question.txt`. Edit it if you want, then send it with
`researchzosho research ask`. On the web pages, the "Sharpen it first" button does the same and
fills the form in with the result.

### What happens during a run

1. Planning. If you gave sub-questions, they are the plan. If not, the library works out who studies
   this kind of question and what each would ask. It makes sub-questions from that.
2. Reading. Each sub-question gets its own reader. The readers work at the same time. A reader
   searches, opens the best sources, reads them, and writes down each fact with its source and a
   quote. Search results are marked by kind (journal, primary record, reference work, blog, forum).
   Readers prefer the first three.
3. A critic checks for gaps. It can send readers back for a second round.
4. The write-up is written: the answer, then where the sources disagree, then what could not be
   settled. The writer works from the readers' notes. It may fetch up to six pages nobody read, to
   verify something doubtful (`RESEARCHZOSHO_WRITEUP_FETCHES`), and a page it fetches counts as read.
5. Checks. The write-up's answer is first compared with your question: is it about what you asked?
   When it is not, the write-up is set aside, and written once more if the run's limits leave room.
   If that one does not answer the question either, the report is the readers' findings alone, and
   its first sentence says so: "The write-up did not answer the question, so this report is the
   workers' findings as they stand." Then the library adds a table of every fact with its source and
   quote. It adds a numbered list of sources, in which copies of one text count as one. Each sentence
   that cites a source is then compared with that source. Sentences the source does not support are
   marked.

The write-up is saved as a draft, with the readers' notes attached. The review that follows reads
its claims out and accepts the report. A report waits for you instead, still a draft, in two cases:
its write-up did not answer your question, or started on it and turned to another subject; its checks
marked sentences that were never sent back to the writer. A page the write-up names that no worker
read and the library holds no copy of is not a reason to wait: it is listed apart from the references,
under "Named by the write-up, not read", and a sentence that rests on it is marked. A page the library
read in an earlier run counts as read; the check reads the library's copy. Its
Checks section says why, one sentence for each reason. `researchzosho inbox` and the Updates page
list the reports that wait, and `researchzosho accept <the report's id>` accepts one as it stands.

### Limits

With no limit, a run continues until the work is done. You can limit it:

- `--max-turns N`: at most N model steps for the whole run.
- `--max-minutes N`: at most N minutes. The readers stop early enough for the write-up to be
  finished. The write-up itself is never cut short, so the run may go a few minutes over. The library
  learns how long its write-up takes on your model: every run records what the write-up took against
  its estimate, and the next run scales its estimate by the median of the last twenty, between a half
  and one and a half, so the readers' time is not cut short by an estimate made for a slower model.
- `--no-paid`: the paid search places and the paid page reader (Exa, Tavily, your own search service,
  Serper, SerpApi, Brave, Firecrawl) are left off for this run, as they are at their month's limit; the
  free places answer. `paid: false` on `library_research` does the same.

With a limit in steps, the library plans only what fits. The rest goes on the open-questions list. The
report's first lines say which limits the run had and whether it finished within them. In the chat you
say them in words; see "A run's own settings, in words" under Talking to the Librarian.

### Pages it could not read

A page behind a paywall or a login, or a site that blocks readers, is recorded as a request to you.

```
researchzosho requests                              # what it could not read, and for which question
researchzosho add paper.pdf --for <address>         # supply the document yourself
```

After you supply a document, the library uses your copy for that address.

**Naming a file in the question.** A research run cannot open files or run commands on your machine.
It reads the web and the library. If your question names a file that exists, the library reads it in
before the run starts:

```
researchzosho research ask "Recommend books like the ones in ~/Calibre Library that I do not own"
```

A Calibre library or its `metadata.db` becomes a list, and the run can look up what you own. Any
other document is added to the library. This happens only for the library owner's questions.

### When the model declines

ResearchZosho does not decide what you may research. The model you chose does. When that model
declines to do something, the library says so plainly and does not try to get around it: it does not
ask again in other words, and it does not hand the work to another model.

How the library knows:

- A hosted model's server can mark a reply as filtered or as a refusal. Azure OpenAI can stop the
  request itself with its content filter, an HTTP 400 answer with the code `content_filter`. Amazon
  Bedrock can stop a reply with its content filter or with a guardrail on your account. The library
  reads each of these as the model declining.
- A local model sends no mark. It declines in its own words. The library then asks the same model
  one yes-or-no question about the reply:

  ```
  Is this reply the model declining to do the task it was given?
  ```

  With the question, the model reads a few words that say what the step was, such as "list the
  checkable factual claims in a text" or "research this sub-question: …", and never the text the step
  worked on. A reply that says a record could not be found, a page could not be reached, or the
  sources cannot tell, is the work done, not a decline.

Which replies the library asks about:

- Words where the step needed a tool call. These are always asked about.
- Replies that sound like a decline: an apology or an inability, such as "sorry", "cannot",
  "unable", "won't", "decline", "refuse", "申し訳", "できません", "désolé" or "ne peux pas". Only
  such replies are asked about, among: the chat's own answer, a reader's closing summary, a section
  of the write-up, a reply without the data the step asked for, a prose answer that is itself the
  work, the writing read from a picture, an article about a subject, and the genealogy steps. To add
  words, set `RESEARCHZOSHO_DECLINE_WORDS` to them, separated by commas. The words only decide that
  the question is asked. The answer decides whether the reply was a decline.
- Never asked about: the write-up's closing caveats (what stayed uncertain), a section or a summary
  that cites a source or is longer than 800 characters, and a reader's closing summary after the
  reader noted facts.

How sure the answer must be:

- Where a decline would end a research run or throw away work already done (the plan, a reader's
  sub-question, the write-up), only the typed answer counts: the model's own probability of "yes",
  read from its token probabilities under a grammar that allows only yes or no, must be at least 0.8.
  llama.cpp gives these probabilities.
- Everywhere else, a typed "yes" with a probability of at least 0.65 counts. Where the typed answer
  cannot be read (Amazon Bedrock, or a server that ignores the grammar), the model is asked for one
  word, and a plain "yes" counts.
- Anything less is not a decline, and the step goes on. The library writes the reply down, as
  `decline_unsure`, in the run's trace (`catalog/traces/<run>.jsonl`), on the chat turn's line in
  `catalog/chat-turns.jsonl`, and in `catalog/crews.log` for the nightly explorer, the bridges,
  sharpening, explanations, surveys, perspectives and the claims of an absorbed text. The review of a
  new report, the reading of a picture, the articles and the genealogy steps do not write it down.

What happens then:

- A reader whose sub-question the model declined stops, and what it noted is not used. The other
  readers go on. That sub-question is not sent round again. The critic's gap is dropped when it has
  mostly the same words as a declined sub-question, or when the model's typed answer to "Does this
  new question ask for the same thing as this declined question?" is a sure yes.
- When the model declines to plan the research, to write the report, or every sub-question, the run
  ends as declined. Nothing is filed, and the question is not put back on the open questions to be
  run again. The run's result says, for example:

  ```
  The model this library uses (qwen3.8-27b) declined to research this question. ResearchZosho did not try to get around it. What the model said: "…"
  ```

- When it declines only some parts, the report is written from the rest and has a section
  **Declined**, written by the library: which model declined what, and what it said. When the model
  declines to check one sentence's citation, the marks the check already placed stay, and that
  sentence and the ones after it are left unchecked. When the library then does not accept the report
  (for example, because no source could be read), only the sub-questions the model did not decline go
  back on the open questions.
- `researchzosho jobs <J-…>`, the run's page, the chat and `library_job` say it too, with the model
  that declined each part, and `researchzosho stats` counts the declined runs.
- In the chat, a turn the model declined shows the same kind of statement, instead of asking the
  model again. When the model of a tool declines, the other tools the chat asked for in the same turn
  are not run, and the reply says which. When a tool did its own part and the model declined the rest,
  the statement is added under the reply.
- `library_absorb`, `library_meeting` and `library_check` save the text and file its questions
  before they ask the model for its claims. When the model declines to list the claims, the result
  says so in `declined` and in its summary, and for an export it says which conversations.
- A scanned PDF: a page the model declined to read is marked in the saved text with the statement,
  and the other pages are read.
- The genealogy commands: a decline for one person is written in the circulation log and said at the
  end. That person's search is not started, and the others go on.
- The nightly review of a new report, the articles about subjects, the cataloger and the enrichment
  write a decline in `catalog/declined.tsv`, with a hash of what was sent. The housekeeping does not
  send the same thing again until it changes.
- Sharpening a question, explanations, surveys, perspectives, bridges and the reading of a picture
  say a decline the same way. None of these treats a decline as an empty answer.
- The triples, concepts, cataloger, shelf-reading and enrichment crews recognise only a server's own
  mark. A local model that declines there in its own words is not recognised as declining.
- Over MCP and HTTP, a step the model declined answers with the error code `declined` (JSON-RPC
  -32006, HTTP 422) and the statement as its message. Asking again sends the same request to the same
  model, so a program that calls the library does not retry it.

What to do next is your choice: ask a different question, or choose another model.

### What the library leaves out, and how to let it in

ResearchZosho does not decide what you may research. It leaves out a few kinds of material by
default, and you can let most of them in for one question.

Left out by default:

- Pornography, and gore: pictures or detailed descriptions of mutilated or dead bodies, shown to
  shock.
- Step-by-step instructions for making a weapon or an explosive, making an illegal drug, or running
  working exploit code against a system. Writing about these subjects is not left out: their
  history, the law, how an attack worked in general, and how to defend against one.

Always left out, whatever you say: sexual content involving a child, in a page or in a picture.
Nothing of it is saved. The run's log says only that a page was left out, and where it was.

Everything else is researched as usual. Extremist material, for example, is researched like
anything else.

How it works:

- Web search asks for moderate safe search where the search engine has the setting (Brave and
  SearXNG). Wikipedia, Crossref, OpenAlex and the record sources have no such setting. When you let
  in pornography and gore for a question, that question's run searches with safe search off.
- A list of pornography, shock and gore sites takes those sites out of the search results before
  the model sees them, and the library never fetches a page from them. Your own refused sources
  (`researchzosho sources refuse <site>`) are left out of fetches too, not only of search results.
- Every page the library fetches is checked before the model reads it or the library saves it. The
  library asks its own model one short yes-or-no question for each kind of material. The first two
  read the opening of the page and the passages the reader is shown; the last reads the whole page,
  in pieces, and stops at the first yes:

  ```
  Is this page pornography, or gore: pictures or detailed descriptions of mutilated or dead bodies, shown to shock?
  Does this page give step-by-step instructions for making a weapon or explosive, making an illegal drug, or running working exploit code against a system?
  Does this page contain sexual content involving a child?
  ```

  A yes leaves the page out: it is not saved, not shown to the model and not cited. The report has
  a section **Left out** that lists each such page by its address and the kind of material, and
  nothing else. A site on the site list is listed with the list it is on, and where that list came
  from. Where the model's typed answer cannot be read, it is asked for one word. When no
  model answers the check, the page is left out. When that happens for three pages in a row while
  the model answers the research itself, the run stops and says why and what to set: the check
  asks with the same temperature and template settings as the research, so `RESEARCHZOSHO_TEMP=none`
  (a server that refuses a temperature) usually mends it. A model that thinks before it answers is
  handled: an Ollama server is told not to think, and on any server a check that comes back empty
  after thinking is asked once more with room to think, and the answer is read after the thinking.
  A check asked while you wait, when you add a page or send a question, takes at most thirty
  seconds.
- A page, a reading list or bookmarks that you add yourself are your own choice. Only the last
  question is asked about them, and neither the site list nor your refused sources stop them. When
  no model answers that question, the page is saved all the same and marked "not checked yet: no
  model answered". The library asks it at the next housekeeping, or at the next command that has a
  model, and removes the page if the answer is yes, and tells you: in that command's output, in
  `researchzosho status` and on the Inbox page. A survey of your own address is checked the same
  way. A repository the library clones is checked against your refused sources and the site list
  first.

To let the material in for one question:

- At a terminal, `researchzosho research ask` asks when a question may need it:

  ```
  This question may need material the library leaves out of research by default: pornography, and gore: pictures or detailed descriptions of mutilated or dead bodies. A yes lets it into this question's research run only; every other run leaves it out as before. Let it in for this question? (y/N)
  ```

  Only a clear yes lets it in: `y`, `yes`, `はい`, `ja`, `oui`, `sí`, `si`, `sim`, `да`, `是`, `네` or
  `예`, alone or at the start of the answer ("yes, please"). Enter, `n` or anything else leaves it
  out. You can also ask for it
  yourself: `--allow explicit`, `--allow howto`, or `--allow explicit,howto`. In a script nothing is
  asked, the run leaves the material out, and the command prints the line that lets it in.
- In the chat, the Librarian asks the same question under its reply. When it also asks about a
  field's mode, it asks one question, and the next after you answer. The web chat has Yes and No
  buttons. No answer within ten minutes counts as no.
- On the Research page, tick the box "Let in what the library leaves out by default, for this
  question only".
- A program passes `allow: ["explicit"]`, `["howto"]` or both (see LIBRARY_PROTOCOL.md). When it
  did not and the question may need it, the result carries `content_suggestion`, which tells the
  program to ask you and to send `allow` only if you say yes.

A yes counts for that one run. It is never remembered: the same question asked again is asked
about again. The nightly research never lets anything in. A report whose run let something in says
so under its question, for example "This run let in pornography, and gore: pictures or detailed
descriptions of mutilated or dead bodies because you asked for it.", and
`catalog/run-content.tsv` lists such runs.

### When a question is about harming yourself

When a question reads as you asking about harming yourself, the library first shows where to find
help, then asks whether to research it:

```
If you are thinking about harming yourself, you can talk to someone now, in confidence.
- In the United States and Canada: call or text 988, free, at any hour.
- Anywhere in the world: findahelpline.com lists free helplines in more than 175 countries.
- In the United Kingdom and Ireland: call Samaritans on 116 123, free, at any hour.
- In Japan: よりそいホットライン 0120-279-338, free, at any hour; or いのちの電話 0120-783-556, free, every day from 16:00 to 21:00.
- In Australia: call Lifeline on 13 11 14, at any hour.
- In Germany: TelefonSeelsorge, 0800 111 0 111 or 0800 111 0 222, free, day and night.
If you are in danger right now, call your local emergency number.
Do you want the library to research this question? (y/N)
```

The line for your country comes first when your computer's settings name the country, then the
worldwide directory. No, Enter, or no answer means nothing is researched. A yes starts ordinary
research. When the model cannot tell whether the question is about harming yourself, the help is
shown anyway, and the run goes on. A script, a program and the nightly research start nothing for
such a question: a script gets the help and the line with `--allow self-harm`; a program gets the
error `confirm` (JSON-RPC -32007, HTTP 422), whose message is the help and a sentence that tells it to show the help to you, ask
you, and send `allow: ["self-harm"]` only if you say yes. A tool that files several runs at once
files nothing for such a question and lists it with the help. A short question is asked too.

A program's question is read before it is filed, within 45 seconds. When the model is too busy to answer
in that time, the question is filed anyway, and the service reads it just before the run would start. A
question that reads as being about harming yourself is then held instead of started: `researchzosho jobs`
lists it as waiting, with the help, for a day. `researchzosho jobs <J-id> --yes` starts it; `--no` ends
it without research. A program that showed you the help and asked you answers with `library_job`,
`op: "allow"` or `op: "decline"`.

### Pictures in web pages

Every picture's alt text (the text a page gives for readers who cannot see it) stays in the page's
text, where the picture stands, and so does a figure's caption. The page's own pictures are listed at
the end of its text, without the site's logos, icons, tracking pixels, adverts and decorative
pictures: how large the page draws a picture, its role and a caption decide first, and its name only
when those leave it open. The list gives each picture's alt text, caption and address, and the
reading model can have one read by the model that reads pictures, the same way your own pictures are
read. Before a picture is read, the model is asked only this:

```
Does this image appear to show a child in a sexual context?
```

A yes leaves it out. Pictures are never saved to disk; only what the model read from them is kept,
as text. The picture must be JPEG, PNG, GIF, BMP or TIFF. A WebP picture is recognised, and the
library says it cannot read it. A picture whose header says it is more than 250 million pixels is not
opened, and a large one is read at a fraction of its pixels.

### Where the site list comes from

The site list is the OISD nsfw list (https://nsfw.oisd.nl, about 470,000 sites of pornography,
shock and gore; GPL-3.0). It is somebody else's list, and it is separate from your own refused
sources. ResearchZosho downloads it at most once a day into its own folder
(`~/.researchzosho/site-list/`), and every library on the computer uses it. When a download fails,
the list already on disk goes on being used, and the next try is the next day. Until the first
download arrives, a smaller list that ships with the program is used: Sinfonietta's pornography
hosts (MIT) and ShadowWhisperer's Shock list (Unlicense); while nothing has arrived, a failed
download is tried again after an hour. A site on the OISD list or the Shock list covers its
subdomains. Sinfonietta's list is a hosts file, which names exact hosts, so each of its entries
covers that host only: a blog at `someone.blog.fc2.com` is not left out because the list names
`fc2.com`. A site you trust (`researchzosho sources trust <site>`) is never left out by the site
list: your own list outranks somebody else's. A download is used only when nearly all of it names
sites and, when its header says how many entries it has, it has about that many.
`RESEARCHZOSHO_SITE_LIST=off` stops the download and keeps to the list that ships with the program.

## 5. Reading the write-up

The write-up appears on the web pages when the run is done, and in the vault if you use one. It
contains:

- the answer, in sections;
- a table of every fact with its source and a quote;
- a numbered list of sources, with the date each page says it was published;
- the citation check: which cited sentences the sources support, and which they do not;
- the languages the sources were in;
- the pages it could not read, if any.

A diagram the report writes as Mermaid text (a code block marked `mermaid`) is drawn on the report's page, in the
page's light or dark look. The drawing is made in your browser with a copy of Mermaid the library serves itself, so it
works without the internet. "Show as text" under the drawing opens the text it was drawn from. A diagram Mermaid cannot
read stays as its text, with the error under it. A picture the report places is shown at reading size, with its title
under it; a click opens the whole file in a new tab.

### Simpler versions

```
researchzosho explain I-0009 --beginner          # for someone new to the field
researchzosho explain I-0009 --familiar          # for someone who knows the field but not this work
researchzosho explain "free energy principle" --in I-0009        # one term, as used in that write-up
researchzosho explain "free energy principle" --in I-0009 --now  # if the library cannot explain it: a quick look
```

On the web pages, every entry has the choices "beginner", "familiar" and "as written" at the top.

The simpler version is written only from the entry, the claims behind it, and their sources. Each
paragraph names what it comes from and is checked against it. A paragraph the sources do not support
is marked. Words you may not know are listed at the end. Each one links to its own explanation.

If the library cannot explain a term from what it has, it says so and offers two choices: a quick
look (a short run, a few minutes) or full research. Either one runs as soon as a worker is free.

These explanations are kept under `readings/` in the library folder. They are not searched and never
become claims. When the entry changes, the explanation is written again.

### Downloading

A report's page and a dossier's page have two links near the top: Download PDF and Download Word. The file
has what the page has: the answer, how each point was checked, the open questions, the claims with their marks
and the sources, each source a link you can click. If you read the page in another language, the file is in that
language too. A diagram the report draws on its page is kept as its text in the file. From the command line:

```
researchzosho report export I-0009 --pdf
researchzosho report export I-0009 --docx --in ja       # the Japanese translation the library keeps of it
researchzosho dossier "Ada Example" --docx --out ~/Desktop/ada.docx
```

The PDF uses the fonts on your computer. A report in Japanese, Chinese or Korean needs a font for that language
(on Linux, the fonts-ipafont or fonts-noto-cjk package); when no font has some characters, the first page of the
file says which ones could not be shown. A program saves the same files with `library_export`, which keeps them
under `catalog/exports/` in the library.

Every other entry, a claim or a summary, has a Download line on its page: Markdown or a PDF. From the command line:

```
researchzosho export F-0012 --pdf
researchzosho export I-0009 --md --beginner
researchzosho export I-0009 --brief             # the answer alone: no checks, evidence or worker findings, no claims list
researchzosho export I-0009 --docx --out ~/Desktop/statins.docx
```

## 6. Reviewing the claims

The write-up carries its own checks. A sentence whose cited source does not say what it says is marked
`[not supported by the cited source on check]` where it stands; a number that no note or source read in
the run states is marked `[number not in any note or source read this run: …]`. After the checks, the
writer revises each marked sentence against the evidence, and a revision replaces its sentence only
when it passes the same check; "Revision after the checks" under the text lists what changed and what
stands as marked. A program reads the claims as records with `library_get` and `format: "claims"`: each
claim with its text, source, confidence, state and what the checks made of it.

```
researchzosho inbox                     # claims and write-ups waiting for your decision
researchzosho accept <id>
researchzosho dispute <id> "<reason>"
researchzosho retire <id>
```

- `accept`: the claim is now part of what the library knows.
- `dispute`: the claim stays, marked as disputed. The library says so whenever the claim comes up.
  It also lists the other claims that rest on the same source, and which of them have no other source.
- `retire`: the claim is removed from answers. The record of it is kept.

A claim does not count as known until you accept it. The library answers from what you have
accepted. A draft is also accepted on its own when a later run finds the same claim from an
independent source.

A claim with the same subject, relation and object as one on the shelf is the same claim. The library
knows this without asking the model. What happens next depends on the claim on the shelf:

- If it is waiting or accepted, the run's source is added to it, also when a disputed copy of the same
  claim is on the shelf. When you disputed or retired such a copy, a waiting claim that gained the source
  is not accepted on its own: it waits for you. An accepted one stays accepted.
- If you disputed or retired it, and no copy of it is waiting or accepted, the run's claim is not filed.
  Your decision holds against later runs. A note on the old claim says where the claim came back from.
- If the library disputed it by itself, the run's source is added to it and it stays disputed, so that
  later sources can settle it. The library disputes a claim by itself when the review finds another claim
  that says otherwise, when the inventory finds that its source does not say it, or when the retraction
  check finds that a paper it cites was retracted.

The same rules hold when the claim has no subject, relation and object, or words them differently, and
the model finds that it is a claim already on the shelf. The review says which of these it did, in what
`researchzosho review` prints and in `catalog/crews.log`.
A claim from a family's own account or tree file is never accepted on its own, whatever record backs it.

A claim read from a picture carries a note saying what its words were checked against: the model's
reading of the picture, or a transcript a person accepted. `researchzosho inbox` marks the first kind,
and `researchzosho accept` says so when you accept one. `library_inbox` returns the note as
`checked_against`.

The same decisions, on every surface:

| | see what is waiting | decide |
|---|---|---|
| command line | `researchzosho inbox` | `accept <id…>`, `accept --all`, `accept --report I-…` (every waiting claim of one report) · `dispute <id> "<why>"` · `retire <id…>`, `retire --report I-…` |
| web pages | the Inbox page | tick any number, then "Accept the ticked ones", "Retire the ticked ones", or "Dispute" with a reason; a report's heading ticks all of its claims |
| a program (MCP or HTTP) | `library_inbox` with `op` list | `library_inbox` with `op` accept, dispute (`why`), or retire, and `ids`, one `id`, or `report` |
| the files | the claim's file under `findings/` in the library folder | change its `state:` line: `draft` to `accepted`, `disputed`, or `retired`; the nightly housekeeping re-reads the files, `researchzosho refresh` does it now (a removed file leaves the search too) |

Sorting a full inbox: the Inbox page, `researchzosho inbox`, and `library_inbox` filter the same way
as the open questions (section 11). The filters are: the report the claim came from (grouped, one
box per report), why it waits (a new claim, or a review that went stale), the kind of claim, the
strongest source's tier, confidence, who wrote it, subject, language, and words in the title. On the
command line:
`inbox --report I-0025 --kind extraction --tier reference --confidence high --writer explorer
--state draft --subject lead-paint --language english --grep "lead paint" --by-report`.

The two pages point at each other. A report's group on the Open questions page links to its claims
in the Inbox. A report's group in the Inbox links to its open questions. Accepting a report's claims
turns its questions' "what became of it" from "still in the inbox" to "kept".

### What a search did not find

A search that looks for something and does not find it has not shown that it does not exist. It has shown
where somebody looked, on one day. The library keeps that apart from the claims, as a dated list:

```
researchzosho looked                         # every line: the date, what was looked for, where
researchzosho looked "Endo Genzaburo"        # the lines about one subject
researchzosho looked move F-0994-…           # an older "nothing was found" claim becomes a line here, and the claim is retired
researchzosho looked add "Endo Genzaburo" --where familysearch --what "Endo Genzaburo 1872"   # a search you made yourself
```

A line reads like this: `on 2026-09-20 a search found nothing about Endo Genzaburo: No patents by him were
found. Looked in: patents.example. [I-0011-…]`. The next search about the same subject is shown these lines as
places somebody has already looked. It is told to look elsewhere first, and to look in the same place again
when it has something the earlier search did not have: a new collection, another spelling of the name, new
access. The list grows as searches look again, so you can see over time where people have looked and when.

`looked add <person or subject> --where <site> --what <the words you searched for>` writes down a search
you made yourself, on a site or in an archive, dated today. The next search about that subject is shown
it like any other line.

## 7. Asking what the library already has

The command line reads the shelves without a model, on the library here as on one connected to elsewhere:
`researchzosho search <words>` lists the hits with their ids, `get <id>` opens one entry, `read <address or id>` shows
a saved page as text (`read <I-…> --in ja` reads a report in Japanese, translated by the model and kept),
`established <claim>` says whether the shelves establish a claim, `changes` lists what changed with a cursor to
continue from, `submit "<claim>" --source <url>` files a claim of your own as a draft, and `help <words>` looks the
words up in this documentation.

```
researchzosho ask "what do I have on keigo in subtitles?"
```

The answer lists each matching claim with its source, its state (accepted, disputed or draft), its
subjects, and nearby open questions. The answer is built from the entries themselves, not written by
a model. If there is nothing, the library says so and records the question on the open list.

Some questions are looked up directly:

- an entry id, such as `F-0004` or `I-0009`, returns that entry;
- a web address returns the saved copy of that page, if the library has one;
- "what changed since last Monday" returns the changes list;
- a subject's name returns that subject.

## 7b. Removing a report or a claim

`retire` keeps a claim on disk and stops using it. `remove` deletes it.

```
researchzosho remove I-0007-recommend-five-science-fiction                 # the report and its claims
researchzosho remove I-0007-recommend-five-science-fiction --report-only   # the report; the claims stay
researchzosho remove I-0007-recommend-five-science-fiction --claims-only   # the claims; the report stays
researchzosho remove F-0081-the-fifth-season-jemisin                       # one claim
```

Rules:

- A claim that another report also lists or cites is not removed. The plan names it.
- `--claims-only` also drops the removed ids from the report's list of claims.
- Pages fetched from the web during the run are not removed. Other claims may cite them.
- The command prints the plan and asks `y/N`. `--yes` skips the question.
- Each removal is written to the changes log, so anyone who cited the id can see it is gone.
- There is no undo.

In the browser, each report and claim page has a "Remove…" section for a signed-in patron with write
access. It shows the same plan and asks for one more click.

## 8. Adding your own documents

```
researchzosho add thesis.pdf
researchzosho add https://example.org/paper
researchzosho add ~/papers --collection thesis --register
researchzosho add papers.zip
```

Supported formats: PDF, Word, PowerPoint, OpenDocument, EPUB, HTML and plain text. The library stores
the text, where it came from, when it was added, and the captions of figures and tables. If a paper
prints its DOI, the library looks up its citation.

PDFs read best when poppler's `pdftotext` is installed. On Debian and Ubuntu, run
`apt install poppler-utils`. On a Mac, run `brew install poppler`. The library uses it when it is
present, for fetched pages and added files. Otherwise it uses its built-in reader. A two-column report
that the built-in reader garbles usually reads cleanly with `pdftotext`.

Sometimes the runner cannot fetch a PDF at all, because a server never answers or a wall blocks it.
Then use `researchzosho add <file> --for <url>`. Download the file yourself and add it. The shelves
re-check against it.

Adding a folder makes a collection. With `--register`, the housekeeping checks the folder for new or
changed files.

An archive (zip, tar, tar.gz or tgz) is unpacked into a folder of the library's own and shelved as a folder
is, under a collection named for the archive unless `--collection` names one. The folder is removed after.
An entry whose path would land outside that folder (`..`, an absolute path), a link, and an archive inside
the archive are skipped, and the command lists them. Unpacking stops at 5,000 entries or 512 MB unpacked,
and then nothing is added; a bigger archive you unpack yourself and add as a folder.

### Keep or link

By default the library stores the text it extracts from each file. It never copies the file itself.
A folder of PDFs takes a few percent of its size on the library's disk.

Sometimes even that is too much, or you do not want a second copy anywhere. `--link` reads the files
where they are:

```
researchzosho add /mnt/archive/papers --collection archive --link --register
```

A linked file gets an entry with its path, a hash of its bytes, and its size. Nothing else is stored.
When the library needs the text, it reads the file. If the drive is not mounted at that moment, the
entry says so: "not reachable now: /mnt/archive/papers/x.pdf cannot be read; mount the drive". It
reads the file again once the drive is back. When a linked file changes, the next rescan sees the new
hash. It reads the new text and marks the claims that depend on that file for review.

A mounted network drive works like any other folder. A URL is always kept, because web pages change.

To count before you decide:

```
researchzosho add /mnt/archive/papers --survey
```

It prints the number of files by type, their size, how much disk keeping the text would take, and the
free space on the library's disk. It shelves nothing.

### From the web pages

The Add page, "Add" under "all pages" in the header and linked from the chat's composer, takes files from your
own computer: a PDF, a Word file, a presentation, an EPUB, a web page saved as HTML or a plain text file, one
or several at once, up to 500 files and 64 MB in one go; a whole folder, with the folders inside it, which
becomes a collection named for the folder unless you name one; an archive (zip, tar, tar.gz, tgz), unpacked
and shelved as a folder is; and a page or a document on the web by its address; each with a collection to
shelve it under if you like. The page says what went onto the shelves and what was not kept, and why. The
library reads the text onto the shelves; the files themselves stay yours. Files the library does not read are
left out. An archive's entries that would land outside its folder, its links and the archives inside it are
skipped, and past 5,000 entries or 512 MB unpacked it is not added at all. A folder too big for the page, or a
file to be read where it lies, goes in at the command line on the machine that runs the library.

### From the chat

The composer's Attach takes one file or several at once, or an archive; they go onto the shelves with the turn,
and the turn says what came of each. In the browser you can also drag files onto the chat and drop them: while
they are over the page it says "Drop to attach", and each file lands in the box as a chip with its name, its
size and a × that takes it off before you send. A picture copied to the clipboard and pasted into the box is
attached the same way. One message carries up to 10 files and 64 MB all together; past ten, the page says so
and keeps the first ten. The Attach button takes several files at once too, so nothing needs the dragging. You
can do the same in the chat with the Librarian in words:

```
> read in /mnt/archive/papers
There are 12,400 documents (11,900 pdf, 500 docx), 38 GB. Keeping the text would take about 1.1 GB;
the library's disk has 3.2 GB free. Keep it, or read it in place?
> read in place
Collection archive: 12,388 added, 12 skipped. Read in place, nothing copied. …
> research the history of the Saros dial from those
```

The last line starts a research run with sources=shelves on that collection. A program does the same
with the `library_add` tool, with mode `keep`, `link` or `survey`. Paths are accepted only from the
terminal or the library's own pages. Any patron with write access can add a URL. The chat takes a path from
the library's owner; a file of anyone else's goes in on the Add page.

### A conversation you had with another assistant

If you worked through a subject with ChatGPT, Claude or another chat, `absorb` reads the thread:

```
researchzosho absorb ~/Downloads/conversations.json
researchzosho absorb saros-chat.md --verify
```

It accepts ChatGPT's and Claude's export files, any `[{role, content}]` list, and a text or markdown
transcript with `User:` and `Assistant:` markers. Three things happen:

- The transcript is shelved unchanged in the collection `conversations`, so search can find it.
- Your questions from the thread join the open questions. The housekeeping's explorer works on them at
  night, and "find out" in the chat picks them up.
- The assistant's statements come back as a list of claims to check. None of them becomes a finding,
  because an assistant is not a source. `--verify` files one research run that checks the claims
  against real sources and reports which hold.

From an export with many conversations, it takes the first 25 and tells you how many remain.
`--limit` takes more. In the chat, "absorb this thread" with a path or the pasted text does the same.
The Librarian then offers two follow-ups: check the claims, or research the thread's main question.

### A code repository, a paper, a website, an issue tracker

`survey` reads one of these and suggests research questions about it.

```
researchzosho survey ~/src/tidebook
researchzosho survey https://github.com/someone/tidebook
researchzosho survey ~/papers/kalman-tides.pdf
researchzosho survey https://arxiv.org/abs/2401.00001
researchzosho survey https://tidebook.example/pro
researchzosho survey https://github.com/someone/tidebook/issues
```

The kind is detected from the argument. `--kind repo|paper|site|issues` overrides it.

- Repository: a folder, or a git url. A url is cloned with git. If git is not installed, the command
  stops and prints the clone command to run by hand.
- Paper: a PDF or document file, a DOI, or an arXiv page. An arXiv page is read as its PDF.
- Issue tracker: a GitHub issues page, or an export file. From GitHub it reads the newest 100 issues
  through the API.
- Website: any other url. One page.

What it does:

1. Shelves the text.
2. Files one draft claim: what this is, what it does or says, what it claims, what it rests on. For
   a paper or a discussion, also what it leaves open.
3. Prints numbered directions. Each is a research question about the thing. For example: the
   published basis of a technique the code uses, or whether a paper's main claim holds against other
   sources.

Nothing runs at this point.

```
researchzosho survey ~/src/tidebook --pick 1,3
researchzosho survey ~/src/tidebook --do "compare its caching with what the papers recommend"
```

`--pick` starts a research run for each direction listed. `--do` starts a run for your own question.
A run takes the model about half an hour. Directions you do not pick stay on the open questions. The
nightly explorer may take them. `researchzosho repo` is the same command for a repository.

A run about a repository the library cloned under `raw/repos/` reads its code with the `read_code`
tool: it lists the files of a folder, searches every file for a word or a pattern, and reads a file with
its line numbers. A run is about the repository when it is the survey's own run, when its collections name
the repository, or when it is a software question that names it; a question that only uses the name as a
word, such as "react", is not given the tool. The tool reads that repository's folder only. It does not
follow a link out of it, and it skips `.git` and build folders. A search stops after 5,000 files, 2,000
matching lines or 10 seconds, and says where it stopped. A README says what is planned as often as what is built, so the run
settles what the software does from the code and cites the file and the line, written
`raw/repos/<repository>/<path>:<line>`. The citation check reads the lines these point to, and so
does the inventory when it re-reads an accepted claim. Such a citation is not your own document: its
source tier is `code`. A claim that rests on one file of the repository stays in the inbox until a
second, independent source backs it, as a claim from any source other than your own documents does.
Other lines of the same file count as the same source, and so do a copy of the file's text on the web
and the file's page on GitHub, GitLab or Codeberg. The repository's own page there counts as its README.
A report marks other lines of a file it already cites as the same file. A repository surveyed from a
folder of your own is read in place and is not under `raw/repos/`, so its runs do not get the tool.

In the chat, "look at ~/src/tidebook" or "read this paper" with the file returns the summary and the
directions. "do 1 and 3, and also check X" starts the runs.

### A list of things

`items` reads a list of books, tools, places, products, or anything else. The list is one item per
line, a markdown table, or a CSV.

```
researchzosho items books.md
researchzosho items reading.csv --column title --lens "{item}: its main argument and how it was received"
researchzosho items tools.txt --as runs
```

The list is stored whole under `lists/` in the library. Each item is checked against the shelves and
marked held or not held. Then each item becomes a question through the lens. The default lens asks
what the item is, who made or wrote it, what it is for, and what is known about it. `--lens` replaces
it. `{item}` marks where the item goes. A note after the item is appended to the question. The note
is the rest of the line, as in `Longitude — Dava Sobel`, or the other columns of a CSV.

- `--as frontier`, the default: one open question per item. The nightly explorer works through them.
- `--as runs`: research runs now, one lane per item, eight items per run.
- `--as none`: only report what is held.

In a CSV, a column named `title`, `name`, `item` or `book` is used. `--column` picks another.

One call looks at, files or runs at most 200 items. To choose which:

- `--match <text>`: keep items whose line contains the text, such as a tag, an author or a year.
  Several `--match` flags must all match.
- `--sample <n>`: take n of the matched items at random.

**A Calibre library.** Give `items` the library folder or its `metadata.db`:

```
researchzosho items ~/Calibre\ Library --as none
researchzosho items ~/Downloads/metadata.db --as none
researchzosho items ~/Calibre\ Library --match "Le Guin" --as frontier
researchzosho items ~/Calibre\ Library --match Sci-Fi --match 2019 --sample 10 --as runs
```

It opens `metadata.db` read-only, so Calibre can stay open. If it cannot read the database, it reads
the `metadata.opf` file beside each book. Each book is one item. The note holds authors, year, series,
tags, ISBN, publisher and formats. Start with `--as none` on a big library.

**What you own.** `holdings` searches every list you have shelved:

```
researchzosho holdings wizard earthsea le guin
```

Every word must appear in an entry. Case and accents are ignored. The chat uses it for "do I have
this" and "recommend something I don't own". A research run has it as a tool. It checks each title
against your shelf instead of guessing from a search result.

In the chat, give the Librarian the list and say what you want to know about each item. It reports
what is held, then files or runs.

### Your own draft

```
researchzosho check chapter.md --verify
```

`check` reads a memo, a chapter or notes, in text, markdown, Word or PDF. The draft is shelved in
`drafts`. Its definite statements come back as claims to check. Its citations, as URLs, DOIs or arXiv
ids, are fetched onto the shelves next to it. A citation that cannot be read becomes a source request.
You answer it with `add <file> --for <url>`. Questions in the draft join the open questions. `--verify`
files one research run that checks the claims against sources, including the draft's own citations.

### A reading list

```
researchzosho reading library.bib
researchzosho reading papers.csv --watch
```

`reading` accepts BibTeX, RIS from Zotero or EndNote, a CSV export, or plain lines of DOIs, URLs and
titles. Every entry with a locator is fetched onto the shelves, as a collection named after the list.
An entry that cannot be read becomes a source request. An entry with a title but no DOI or URL is
listed. Give those to `items` to research them by name. With `--watch`, the housekeeping re-reads the
pages each night and stores a new copy when one changes. Then research from them with `--shelves` on
that collection.

### A file of questions

```
researchzosho questions file syllabus.md
researchzosho questions file exam.txt --as runs
```

The file has one question per line. They join the open questions in that order. The explorer works
through them at night. `--as runs` starts research runs for the first ten now and files the rest.

### Your bookmarks

```
researchzosho bookmarks bookmarks.html --folder Research --watch
```

`bookmarks` accepts the bookmarks file any browser exports, Chrome's `Bookmarks` file, or one URL per
line. Each page is fetched onto the shelves, in a collection named after the folder. `--watch`
re-reads them each night.

### A meeting transcript

```
researchzosho meeting sync.vtt --verify
```

`meeting` accepts WebVTT from Zoom, Teams or Otter, or lines of `Name: words`. The transcript is
shelved in `meetings` with a list of the decisions at the top, so you can ask later what was decided.
Questions anyone raised join the open questions. Claims come back to check, with who said them.
`--verify` files the run.

All of these work from the chat too. Give the Librarian the file or paste the text, and say what it
is.

To ask a question that uses only your documents:

```
researchzosho research ask "…" --shelves
```

With the default setting, `both`, readers use your documents first and the web second. A program can
also name a collection with the `collections` field of `library_research`.

When a research run in the software field has found projects, the projects can be read in one go:

```
researchzosho survey --from I-0042              # the repositories the report names, the first five cloned and read
researchzosho survey --from I-0042 --top 10 --do "how does it read a GEDCOM file, and what does it do with dates it cannot parse?"
```

Each is cloned into the library and read the same way as one given by hand, in the order the report's
table puts them, and `--do` files that research question about each one.

## 8b. Translating a document

A document of your own, translated into another language and written out as a file: `researchzosho translate
<file> --to Japanese`. The formats read are txt, md, pdf, docx and epub; the file is written beside the original in the
same format (`--as md|txt|pdf|docx|epub` chooses another), and a note beside it, `<name>.<lang>.translation.md`, says
what was done. Both the original and the translation go onto the shelves, the translation under the original's
collection with a link to it. From the chat: "translate this file into Japanese" with a file attached, or naming a
document on the shelves by its title ("translate saros notes into German as a PDF, reviewed"). "I would like this epub translated
into English using DeepSeek V4.1 Flash and have Kimi K3 do a thorough check" names the translator and the reader by an
entry's name or its model (`researchzosho models` lists them) and the tier by the check asked for; "checked by the
strongest other model" lets the library choose; without a word, the model the chat talks to (`/model`) translates and
the judge seat reads. The checked tier scores every unit with LaBSE and MetricX-24; where they are missing, `researchzosho
setup` asks first with the sizes (about 1.9 GB and 5.7 GB), the daemon sets them up in the background when it starts, so an
update brings them too and refreshes MetricX's script when a release changes it, the Translate fold has a button, and
`researchzosho translate scorers install` does it by hand;
`RESEARCHZOSHO_SCORERS=off` leaves them out on a small machine. A name no entry matches, or an
entry whose host has no key yet, is answered before anything runs: the models that can run now, the line that adds the
entry named (DeepSeek, Kimi, Claude, GPT, Gemini, GLM and MiniMax are known) and the line that keeps the host's key, with
where the key comes from; the Settings page does both under Models. A paid call refused during a run ends it with what
the status means (the key, the credit, the rate limit, the model id) and the models the run can be sent again with. On the Keep page, the
Translate fold takes a file, the language, the format and the tier and files the work as a run; the run's page has the
file, the note and the download links. A program uses the tool `library_translate`.

The document is cut into its units in order (headings, paragraphs, list items, table cells, captions; an EPUB chapter
by chapter) and each unit is translated with the glossary and the kept words, then the document is rebuilt from the
translated units in the same structure. The structure is kept; the layout is not: the fonts, the styles and the pages
of the original are never read.

| in → out | what is kept |
| --- | --- |
| epub → epub | the chapters, the navigation, the pictures, the package's title, creator and language; the original title stays in the metadata |
| docx → docx | headings, lists and tables, through the library's own writer with its styles, not the original's; pictures are not carried |
| pdf → pdf | a new PDF laid out from the text; the pages, columns and pictures of the original are not carried; a short line on its own is read as a heading |
| md → md | every block, with code blocks and front matter as they were |
| any → txt | the words alone |

Kept as they stand: names (with the original in parentheses where they first appear), numbers, dates, addresses,
citations in brackets, code blocks and front matter.

Four tiers (`--quality`, the same words in the chat, on the form and on the tool):

| tier | what runs | when to choose it |
| --- | --- | --- |
| `quick` | one pass with the glossary and the kept words | you want it as soon as possible |
| `checked`, the default | one pass; then the checks a program makes with no model (every unit present on both sides, every number, address and citation of a unit in its translation, every glossary term rendered as the glossary says, the length within a band); then a program's score for every unit, LaBSE's closeness of meaning between source and translation and, when it is installed, MetricX-24's quality estimate; a flagged unit is translated again in a fresh call and the revision kept only when its score improves; with one model only, a sample of units is translated back and compared | the honest middle: no second model, nothing spent |
| `reviewed` | checked, then a second seat reads source and translation side by side, the units the checks flagged and a sample of the rest (a fifth, spread through the document, more where the scores are lowest; a document under 150 units is read whole, and the note says so), and annotates each error with a fixed typology, accuracy (mistranslation, omission, addition, untranslated), terminology (against the glossary), fluency (grammar, wording), style (register), a severity (minor, major, critical) and the words concerned; a unit with a major or critical error is translated again against its flags and read again | the extra reading, for a document that matters, at its possible cost |
| `thorough` | reviewed, with every unit read | the extra reading over the whole document |

A unit still flagged after everything is marked in the output with a bracketed note in the document's new language
and listed in the translation note; a minor error is listed and the unit left as it is. What counts as a bad score is
never a fixed number: the thresholds are measured per language pair from the scores of the units accepted so far
(the lowest tenth of LaBSE, the highest tenth of MetricX-24, once twenty are on record), and a flag always shows the
score. A unit under 40 characters, a heading, a table cell or a caption, is scored and shown but never flagged by its
score: on a short unit the scores reward the literal word over the right one (a cell "Cells on the rim" scored 0.47 as
縁の区画数 and 0.81 as 細胞, the biological cell), so the program's checks and the reader are what judge those. The first units of a pair come from a translation read by hand: `researchzosho translate scores accept
<translation note> --units 1,3-5` puts the units you found right onto the record. The note names the tier, the
scorers that ran and their models, the thresholds, every unit's scores, the flags with their scores, what changed
(each revision's scores before and after, kept or not), the reader if any and what it read, the glossary and the
shelves.

The scorers. LaBSE (sentence-transformers/LaBSE, Apache 2.0, 0.5B, 109 languages) is served by the same embeddings
image as search by meaning: on the first checked run the library says the download's size (about 1.9 GB) and starts
it through Docker, or, when Docker is not there or the start fails, runs the program's checks and the back-translation
sample alone and the note says the scores were not available; `RESEARCHZOSHO_LABSE` names a server elsewhere, or
`off`. MetricX-24 (google/metricx-24-hybrid-large-v2p6, Apache 2.0) is optional and off until installed:
`researchzosho translate scorers install` says the sizes first (about 4.7 GB for the model, up to twice that when both of
its weight files are fetched, about 1 GB for its Python packages) and sets it up in a Python environment of its own; until then the tier runs on LaBSE alone and the note
says so. One MetricX-24 process scores a whole translation, the model loaded once; a process that gives no score for
five minutes (twenty for its first, which includes the load) is ended, the rest of the translation goes on without it,
and the note says so. `researchzosho translate scorers status` says what is there.

Who reads, for `reviewed` and `thorough`, depends on what is set up (`researchzosho models`, section 12):

| what is set | the reader | and |
| --- | --- | --- |
| one local model only | the same model, in fresh calls | the note says so, and that it is the weaker check; a sample of units is translated back into the source language by it and compared with the source, mechanically and by a reading of the pair (`--back-translate off` turns that off) |
| two local models (a judge seat, `RESEARCHZOSHO_JUDGE_DRIVE`, or a second entry) | the judge seat, or the entry named with `--judge <entry>` | the note names it; no back-translation unless asked |
| hosted entries | the judge seat, or `--judge <entry>`; the translator too with `--model <entry>` | a paid call: the cost is said first, about two reads of the document at the entry's prices for the reader, one read and one write for the translator; the run stays inside the entry's monthly cap and goes on the tally; nothing hosted is spent unless you chose that entry |
| `--judge auto` | the strongest configured entry that is not the translator, local before hosted | the note says which it chose and why |
| no model at all | — | a translation is not offered: one sentence says to connect a model |

The glossary. `researchzosho glossary add "Acme Corp" ja "アクメ社"` fixes how a term is rendered in every
translation into that language; `glossary list` and `glossary remove` manage it; in the chat, "render Acme Corp as
アクメ社 in Japanese" or "用語集：Acme Corp → アクメ社（日本語）" does the same, and the Translate fold on the Keep page
has a line for it. The translation note says which entries applied, and which recurring terms the model fixed for
the one document.

The time is said first, from the number of units and the speed measured on the model in earlier translations: the
checked tier's time, then quick's, reviewed's and thorough's beside it in one line ("about 3 minutes checked, the
default; quick about 2 minutes, reviewed about 5 minutes, thorough about 7 minutes"); the first translation on a
model says it is a first guess. The chat asks which tier, or whether to go ahead when the tier was named and the run
is long; the command line prints the estimate and proceeds; with the library's service running, the command files
the run and waits on it (`--no-wait` returns the run's id), and with no service it runs in the terminal. A long
document runs as a job with its progress in units, on the Runs page and in `researchzosho jobs`.

## 8c. Pictures

Pictures are a capability of the library, used three ways: finding a picture or a set of them for words; using pictures
as evidence, judged against what you are looking for; and starting from a picture you have. This release builds the
first two by words. The third has its place in the code and nothing behind it yet: it will rank the candidates of a word
search by their likeness to a picture you give, by an image embedding, since no open reverse-image search is there to
call.

**Finding.** In the chat: "find me some pictures of the Antikythera mechanism", "show me 5 photos of a red fox in snow",
"Xの画像を探して". At the command line: `researchzosho images find red fox in snow --count 8`. The candidates come from
Brave's image search when your Brave key is kept (the Settings page, under Sources and keys; it counts against the same
monthly limit as web search) and from the library's own SearXNG with images as the category (`researchzosho setup`
starts one). The two lists are merged rank by rank, a picture that both found is shown once, and your refused sources are
left out. Each candidate is said with the page it stands on, its size when the engine said, the engine that found it
and when; the chat shows a small copy of each. Nothing is written to disk by a search.

**Keeping.** "Keep them", or "keep 1, 3", or `--keep` at the command line (`--collection <name>` shelves them under a
collection). Each picture is fetched once, 8 MB at most, a picture format only (JPEG, PNG, GIF, WebP, BMP, TIFF). Before
it is written, the model that sees pictures is asked the one question every picture from the web is asked (section 4,
Pictures in web pages); a yes is not kept, and the answer says so. When no model sees, that check cannot run and the
picture is kept as you chose it, from a search with safe search on. The bytes go under `catalog/images/<name>` and an
entry goes onto the shelves with the page, the engine, the time it was found, its size and, when it was judged, the
verdict and the reason, so the picture can be cited as evidence; `researchzosho search` finds it by its words, the
pages show it at `/images/<name>`, and the chat shows it small.

**Judging.** "Which of these show a bronze gear?" after a find, or `researchzosho images find "Antikythera mechanism"
--judge "a bronze gear with teeth"`, or `researchzosho images judge "a red fox" ~/Pictures/fox.jpg
https://example.org/animal.jpg`. Each picture goes to the seeing model with one question, "Does this picture show …?
Answer: yes, no or unsure, then one sentence why", and comes back yes, no or unsure with the sentence; a picture that
could not be fetched or answered is marked not judged, with the reason. This is how a picture becomes evidence for or
against what you asked.

**Describing.** "Describe this picture" with a picture attached to the message, or `researchzosho images describe
<picture> [a question]`, where the picture is a web address, a path on this machine, a kept picture's file name or its
entry on the shelves. The seeing model says what it shows, the people in it when it knows them, the place, the time it
appears to show and any writing in it, in the names a later search by words can use; a question after the picture is
answered too.

**The model that sees.** The library's own model is used when it reads pictures: the default entry when its server was
started with the model's picture file (`researchzosho model install` sets qwen3.8-27b, gemma-4-e4b and gemma-4-e2b up
with theirs), else a local entry that sees (the best tier first), else a hosted entry that sees and has its key; a server
you set for pictures (RESEARCHZOSHO_VISION_DRIVE and RESEARCHZOSHO_VISION_MODEL) is taken at its word. Whether an entry
sees is measured, not assumed: `researchzosho models test <name>` sends a small red square and asks its colour, and a
local entry a blue one after it, so a blind guess cannot pass; the answer is kept on the entry and `researchzosho models`
shows "sees images", "does not see images" or "not probed for pictures". A local entry added with `researchzosho models
add` is probed at once when its server answers; a hosted one is not, since the call is paid — `models test` asks it, one
small call. `--sees yes|no` on `models add` or `models set` says it outright. MODELS.md says which of the measured models
read pictures. When no model sees and you ask for a judgement or a description, the answer says what the library has,
which entries were not probed and the lines that add one, local first: `researchzosho models add vision openrouter
deepseek/deepseek-v4-flash-vision-exp` then `researchzosho models key openrouter <key>` is the hosted way.

**In a research run.** Every worker of a run that goes to the web has two more tools. `image_search` takes the words
the worker builds from what the run already knows, an event's name, a person's name, a place, and answers with the
candidates, each with the page it stands on, its size and the engine that found it; the pictures the library already
keeps come first. `image_judge` takes the worker's criteria ("Valerios Stais", "the Antikythera wreck being salvaged")
and the candidates' addresses, and the seeing model answers for each: yes, no or unsure, with one sentence why. A
picture judged yes is kept on the shelves with the verdict in its head and becomes evidence of the run: the worker
notes it as it notes a page, with the picture's address as the source and the verdict's sentence as the quote, and the
report's references number it like a page, the line saying it is a picture, where it was kept, the page it stands on
and the verdict. A worker makes at most three picture searches on its sub-question and judges at most eight candidates
a call; the turns are its own. The writer is handed the one line that shows a kept picture, `![title](/images/<file>)`,
and may put it under the paragraph that cites it, at most five in a report, the ones it asked for itself (below) first;
a line for a picture the library does not keep is taken off. On the page a placed picture is shown at reading size, and a
click opens the whole file in a new tab. The run's trace carries each search (`picture_search`) and each judgement (`picture`), the research
log counts the searches under `images`, and a hosted seeing model's tokens go on the tally as the translator's do. When
no model sees, `image_judge` says so and how one is added, and the run goes on without pictures.
`RESEARCHZOSHO_PICTURES=off` leaves the tools and the step below out of every run.

**The chain.** In two parts. Right after the plan, before any worker takes a turn, the run looks for pictures of what the
question itself names, a person, an organisation, a place, or the event it says by a year and an event word ("the
eruption of 79 AD"), at most three: the name, with the question's own subject beside it, to the engines; the
candidates judged; the yeses kept as evidence, two a subject at most, one turn each. Those turns are kept back from
the workers at the start of the run, one a subject and never more than a sixth of the workers' share, and the log says
how many ("pictures: 2 turns kept back from the workers for pictures of …") and then what was searched and why
("pictures: "Valerios Stais" searched because the question names Valerios Stais: 6 found …, 6 judged …, 2 kept"). After
the workers, the second part: the full names the findings raised, a person the sources name, an organisation, an
artefact, an event (a single word the question does not name, a passing place such as the museum's city, is not a
picture subject; a people's adjective, "Greek", is not a name; nor is a phrase of titles, periods and peoples alone,
"Ephor General", "Hellenistic Greek"), on the turns and the time left, so that an event leads
to the people in it and they to their pictures; when it cannot run the log says the numbers, "no time left for
pictures of …: 17.0 min left, the write-up's estimate is 17.7 min", or the turns used against the ceiling. The judge is
asked for the entity in the question's own connection ("Antikythera, in connection with Valerios Stais"), told that a
yes means the picture shows the thing itself, the person's likeness, the object, the scene, and not something of the
same culture or period, and asked to say what it relied on, the page's title, a caption in the picture, or what it
sees. A picture that comes twice, under two addresses or the same address with a tracking string, is judged and kept
once. The trace carries each step (`picture_chain`, with when it ran) and each judgement (`picture`). A name a worker
already searched is not searched again. The next step, finding other pictures of a person from a picture judged to
show them, is the next increment: a kept picture of a person is its seed, and the likeness search (an image embedding
over the candidates of a word search) is the seam behind it.

**Exports.** A report's PDF and Word file embed a kept picture where the report shows it, scaled to the column, with
its caption under it: the picture's words and the page it came from. A picture the library does not hold, or one the
writer cannot read (WebP), is left as that caption in its place. The Markdown export keeps the picture lines as they
are.

**Pictures in the documents you read in.** When you add an EPUB, a PDF or a Word file (the Add page, the chat, the
command line, `library_add`, a registered folder), its pictures are kept too: each under `catalog/images/` with an
entry of its own, kind image, the document's locator as its page, the caption or alt text as its title, `document` as
the engine; the document's text keeps a picture line pointing at the library's copy, so the document's page shows its
pictures, and a research run finds them on the shelves before it asks the web. An EPUB's pictures stand where its
chapters put them; a PDF's and a Word file's, whose places in the text the readers do not know, are listed at the end
of the text under their own heading with the page each was on. Small pictures, furniture under 64 pixels a side or a
kilobyte, are left out; at most sixty a document. The seeing model describes a kept picture once, the first twenty of a
document, so a later search by words can find it; a picture already on the shelves is not described again, so a
rescan costs no call. `RESEARCHZOSHO_DESCRIBE_PICTURES=off` keeps them with their alt text alone. A linked folder's
files are read in place each time, so their pictures are kept but not placed in the text.

**The pictures page.** `/images` (Pictures in the page list) shows everything kept as small tiles, newest first, each
with its page, its verdict and where it came from: the engine, or the document it was read out of; a box finds
pictures for words, the shelves first, and the keeper or a writer keeps one from the results. The Settings page's
Models table says of each entry whether it sees images, does not, or was not probed, and which entry pictures go to.

**A picture as the ask.** Attach a picture to a chat message and say what you want of it: "find more pictures of this
person" (or this building, this object, this coin), "find 5 more photos of this man in Athens", "find more like this",
"この人の写真をもっと探して"; "find places like this", "show me rooms like this one", "use this as the reference for pictures
of Kyoto", "この写真と似た場所を探して"; "what is this?", "who is this?", "これは何？". The picture is kept on the shelves as
yours (source "the person"; the Pictures page says "given by you"), so a later ask can cite it by its entry. The seeing
model first describes it, what or who it shows and the words to search by; for a person it gives the visible traits and
never a guessed name, a name only when the picture itself carries one. The search runs on those words and yours, and
each candidate is judged beside your picture, both in one look, for the same person (by face and build, not dress or
setting), the same object (by its shape and marks) or the same place; "places like this" and "use this as the reference"
are the other mode, like yours in the aspect the words name (the kind of place, look and style), not the same one. The
reply says which mode it used and why, how the judging was done, and lists the candidates with their verdicts; "keep
them" keeps the ones you choose. Whether a model takes two pictures in one call is measured once, with two coloured
squares; one that takes one at a time has your picture described in words and the candidates judged against those words,
and the reply says so. When the ask carries a person's name (your words, or the reference's own title), the library reads
each candidate's page title, address and caption for it, tells the judge where the candidate names the person and asks
whether writing in the picture does; the verdict is then ranked, the writing above the model's face reading: a face
match is a yes, writing that names the person is a yes, a candidate named by its page title or its address whose faces
the model reads as different is "unsure", listed with its reason ("named by its address; faces: no → unsure: the page
names Valerios Stais; the faces look different to the model") and not kept, and a candidate named by nothing is a plain
no. A caption names everyone connected with a picture (a coin's description named the man who dug it up), so a caption
naming is told to the judge and said in the reason but does not outrank its reading; and only the picture's own text
counts, never the library's description, search words or criteria. The 27B cannot match one face across decades; the
name written on the picture or in its address can. At the command line: `researchzosho images like <path | url | kept entry> [words…] [--what
person|place|object] [--concept "<aspect>"] [--count N] [--keep]`, and `researchzosho images judge "<criteria>" --like
<reference> <pictures…>`. Over the protocol: `library_images` with `op`=like (`reference`, or `bytes` with
`content_type`; `mode` same or concept; `what`; `words`), and `reference` on `op`=judge.

**Likeness in a run.** When a research run keeps a picture judged to show a person, and the judge says it relied on a
likeness or on a label naming them, that picture becomes the reference for the rest of the search and for the run's
later searches for the same person: the candidates after it are judged beside it, for the same person, and the log says
so ("6 judged against "Valerios Stais" (4 of them beside the picture kept as …)"); the reference line of such a picture
reads judged "the same person (…) as the picture kept as <id>". A picture already on the shelves whose own entry says
it shows a thing or a place (the mechanism's fragments, kept for "Antikythera") is left out of such a pass without a
call, and the trace says so (`skipped`). A worker's `image_judge` takes an optional `reference`, a kept picture's
entry, for the same. A likeness helper of its own (an image embedding ranking the candidates of a word
search) can come later behind the same seam; the judging is the seeing model's own for now.

**Pictures the report asks for.** The library decides them at the plan. Each sub-question whose words name a thing a
picture can show gets one ask, read from the words alone: a place ("Where exactly was the Antikythera wreck found?")
gets a map of it, "a map of the Antikythera wreck site"; a mechanism, a device or a process ("How does the gear train
of the Antikythera Mechanism model the Moon's varying speed?") a diagram of it, "a diagram of the gear train of the
Antikythera Mechanism"; a person a portrait; an object or another named thing a photograph of it. A sub-question that
asks why or weighs significance, interpretation, dating or influence gets none, a section of argument stands on its
words; a phrase of titles, periods and peoples names nothing; and the question's own subject, which the chain looks
for already, is not asked for twice. The log says what will be asked for which sub-question, and the trace says of
every sub-question what it got or why not (`picture_for`, by the library). The asks run after the workers and before
the write-up, on turns kept back at the plan (one an ask, never more than a sixth of the workers' share, beside the
chain's), each with the question's subject beside the words and judged in the question's connection under the same
rule as the chain, a diagram must show this mechanism's gearing and not any gears; the first yes is kept and no further
candidate is judged; one picture a sub-question, five a report. When the write-up's time is up the step stops and the
log says the numbers ("no time left for pictures for the sections on …: 1.0 min left, the write-up's estimate is 10.0
min"). The writer is handed the kept lines in its SOURCES list with the section each belongs to ("it belongs to the
section on "…": to show it, put this line on a line of its own under the paragraph of that section"), numbered as
every kept picture is, and the references carry them with their pages and verdicts. The writer may ask for more with
a tool of its own, `picture_for` (the section's heading, what the picture should show, a kind), which runs the same
ask on a turn of the write-up's reserve and answers the line and the number; one a section, and five a report counting
the library's. The report shows at most five pictures, the sections' own first. The chat's replies do not ask for
pictures this way yet.

**Likeness, measured.** A likeness helper measures first, when it is set up: `researchzosho images likeness install`
puts, in a Python environment of the library's own and on the CPU, open_clip's ViT-B-32 with the LAION-2B weights for
pictures (the code and the weights MIT, 605 MB) and OpenCV's YuNet detector (MIT) and SFace recogniser (Apache-2.0, 39
MB together) for faces, about 1.3 GB of packages beside them; `researchzosho images likeness status` says where it
stands, the daemon sets it up in the background when it starts and the disk has the room (about 4 GB), and
`RESEARCHZOSHO_LIKENESS=off` leaves it out. An update refreshes an installed helper on its own: when a release changes
the helper's script or its packages, the daemon copies the new script over at start, restarts the helper and says so
in one line. With it, every judgement beside a reference — "find more pictures of this
person" in the chat, `images like`, the protocol's `like` and `judge` with a reference, a research run's passes
beside a confirmed portrait — measures each candidate against the reference before the seeing model looks: the faces
when both pictures hold one, else the pictures; the model then judges the best-measured first, and the number joins
the verdict as machine evidence. The face thresholds come from twenty-six public-domain portraits of thirteen people,
two sittings each (their sources are in the test resources): the same person measured 0.50 to 0.92, two different
people 0.09 to 0.34; so a face score at or above 0.45 turns the model's no into unsure ("the faces measure the same
(0.62) while the model says no → unsure: …") and one below 0.30 turns its yes into unsure ("the faces measure
different (0.21) …"), and between them the model decides with the number said ("…; faces measure 0.36"). The picture
embedding does not separate people (the same person's sittings scored 0.59 to 0.95, different people up to 0.67), so
it ranks and is said, and never overrides. The measure was calibrated on studio portraits of the same era, and it
cannot bridge decades or tiny faces: the first live likeness measured 0.22 between a man's bust portrait in old age and
two photographs of him decades younger, one a full-length photograph with a small face. So writing in the picture, the
page's title, its address or a caption naming the person keep precedence over the measure, a yes that rests on them
stands and the why says "faces measure 0.22, but the picture's own words name …", and only a yes that rests on the
faces alone is demoted; and a face under 80 pixels on its longer side, or found with less confidence than 0.7, is not
measured at all, the why saying "the face in the candidate is too small to measure (41 px)" and the picture score
standing in. Without the helper everything works as before and the reply says "no likeness helper is set up; the
model's reading alone". facenet-pytorch was not taken: its code is MIT but its weights
were trained on VGGFace2, a dataset licensed for non-commercial research.

**In the chat.** When your words ask about a thing a picture can show — a mechanism, a place, an object, a person by
name, a process with a known diagram, read by the same rules as a report's sub-questions — the library, once the
model's reply is written, asks for one picture of that thing: the shelves first, then the web, judged under the
shows-rule in your words' connection, and places it under the reply with its line and a sentence that says why it is
there ("Picture: Antikythera mechanism gear train, judged "a diagram of the gear train": yes — … (kept as …)"). At
most one a reply; none when the reply carries a picture already, when your words asked for pictures (that is the
pictures turn above), when the turn started or offered a research run, or when a question is about the models or a
translation. The reply's own words are untouched; one search and at most six judge calls. Japanese questions are read
too ("ピンとスロット機構の仕組みは？" asks for ピンとスロット機構の図).

**What comes next.** Pictures inside a report's own question (a picture sent with a research ask), and the helper's
measure calibrated for things that are not faces.

## 9. Languages

A model left alone searches in English. When a question names a place or a culture whose language
differs from the question's language, the plan gets one extra sub-question: what sources in that
language say. The reader on that sub-question:

- starts from search queries in that language;
- is told when a search it wrote is in the wrong language;
- tries again if the first round found nothing in that language.

The write-up ends with a line that lists the languages of its sources. There are at most two extra
languages per question. The language of the question itself is never added.

A library can have every report written in one language: the setting `language` (`researchzosho settings set
language Japanese`, or on the Keep page). Unset, a report is written in the language its question was asked in. A
question that asks for a language itself is answered in that one either way. The language that was decided is
recorded on the report; a report's line says "Written in …" when that is not English, the default, or when you read in
another language, and "Written in …, read in …" when you read it translated. A report filed before 0.7.0 has no record,
and its language is read off its letters: Japanese, Chinese, Korean or else English.

Each person can read reports in their own language. In the chat, "read this in Japanese" or "この報告書を英語で" about the
report just shown, or one named by its id, or "the dossier on Ada Example", answers with the link to it in that language,
and starts the translation when none is kept yet. A report's page offers Read in: as written, English, 日本語;
"Always read in …" keeps the choice (`researchzosho settings read-in ja` does the same), and from then on every
report written in another language opens translated by the model, the first time after a few minutes, kept until the
report changes. The cites, the claim ids, the dates and the sources stand as they are, the report's line says what it
was written in and what you read it in, and Past says the same of each. A program reads a report in a language with
`library_get` and `in`.

The pages, the chat's own sentences and the command line's fixed sentences follow your language too: the labels, the
titles, the buttons, the notes and the questions the library asks under a reply. The web pages pick it in this order: the
switch in the header (日本語 / English, kept by your browser), the language you read reports in when you are signed in,
your browser's language, the library's `language` setting, else English. The command line follows `RESEARCHZOSHO_LANGUAGE`,
else the terminal's `LANG` when it is Japanese. English and Japanese exist; the descriptions of the settings on the Keep
page stay in English for now, and the model's own words come in the language the report was written in. Another
language is one file, `librarian/src/main/resources/org/researchzosho/librarian/chrome/<code>.json`, English text to
the sentence in that language; a sentence the file lacks is shown in English.

## 10. Checks against bad sources

The citation check confirms that a source says what a claim says. The checks below ask whether the
source itself is reliable. None of them uses a model's opinion.

- **One source is not enough.** Copies of one text count as one source. A source that cites another
  is not independent of it. A claim with a single independent source stays a draft until a second
  independent source confirms it, or until you accept it. When a later run finds the same claim from
  an independent source, the existing claim gains that source and is accepted. Every claim shows how
  many sources it has and how many are independent. The code of a repository the library cloned is
  not your own document: a claim on one file of it waits for a second source like any other. The
  same file reached another way, by other lines of it, a copy on the web or its page on GitHub, GitLab
  or Codeberg, is not a second source.
- **Retractions.** Every claim that cites a paper by DOI is checked against Retraction Watch,
  through Crossref. The check runs when the claim arrives and every thirty days. A retracted paper
  disputes the claim, with the notice as the reason. An expression of concern is noted on the claim.
- **Your own list of sites.**

  ```
  researchzosho sources trust <host> [why]    # counts as a primary source
  researchzosho sources ban <host> [why]      # never shown in searches; claims resting only on it are dropped
  researchzosho sources forget <host>
  researchzosho sources list
  ```

  A rule for a host covers its subdomains. No third-party ratings are used.
- **Dates.** Each source shows the date its page says it was published. A write-up's reference list
  shows the range of dates.
- **Unknown sites.** When a claim cites a site the library has not cited before, the library runs one
  search about the site. It adds a note to the claim with what it found. When the search could not be
  made, the note says so, and never that nothing was found. Journals, archives and sites you trust are
  skipped.

The library does not decide whether a claim is true. It shows what supports the claim. You decide.

## 10b. Archives, old newspapers, Japanese books, code, and other video sites

These are in every run. The model decides which to use for a question, the way it decides between
your documents and the web. None needs a key, except the two marked.

**A page that is gone.** When a page a run wants answers "not found", does not answer at all, or
answers with a wall instead of the page, the run reads the archived copy from the Wayback Machine
or archive.today, if one exists. The report cites the copy and the date it was saved, and its
"Archived copies" section says how many pages were read that way. Nothing to set up; a run that
hits a dead site simply reads the archive.

**`archive_search`** is the tool behind the rest of the web archives and the Internet Archive:

- `copy`: the saved copy of a page nearest a date, to read a page as it was.
- `history`: when a page changed over the years, one line per change.
- `site`: the pages an archive holds under a site or a path that no longer exists (the Wayback
  Machine and Common Crawl).
- `texts`: scanned books and documents in the Internet Archive, by catalogue and by their full
  text, with the page where the words occur.
- `tv`: television news by its captions. `video` and `audio`: films and recordings.
- `wiki`: a Wikipedia article's revisions, and the article as of a date.
- `heritage`: museum and library items in Europeana and DPLA. These two need a key:
  `RESEARCHZOSHO_EUROPEANA_KEY`, `RESEARCHZOSHO_DPLA_KEY`.

**`old_newspapers`** searches digitised historical newspapers by their text: the Library of
Congress (United States, 1770 to 1963), Gallica (France), Delpher (the Netherlands), Papers Past
(New Zealand), and Trove (Australia, with `RESEARCHZOSHO_TROVE_KEY`). For a person, a place or an
event before the web: an obituary, a notice, a local story.

**`ndl_fulltext`** is the National Diet Library's full-text search of digitised Japanese books
whose copyright has expired, about 280,000 books and 80,000 classical works up to about 1970:
local histories, name directories, school and company histories. It finds the books and the pages
where the words occur, and reads the passages of one book. The text is machine-read and
uncorrected, and the report says so. The library's terms allow this use without an application;
a commercial, continuous user must apply to the library themselves.

**`software_archive`** reaches Software Heritage, the archive of every public repository it has
visited, including ones that no longer exist, and Debian's snapshot archive, every version a
package ever had.

**`video_sites_search`** searches the video sites other than YouTube: Niconico (Japanese), Nebula
(independent creators; the catalogue is open, watching needs a subscription), PeerTube (every
instance), Odysee, Dailymotion, and the Internet Archive's films.

HathiTrust is not among these: it blocks machine reads of its full-text search. The 官報 and the
other serials in the National Diet Library are searchable only on its website, not through the
service above.

## 10c. Podcasts

Every run has `podcast_search`: shows by words, a show's episodes from its feed, what is said in an
episode with the time, the episodes a person appeared on, and shows like one. The feed is the source;
Apple's directory answers with no key. Shows found in the Podcast Index's file come in the order of their words and
their latest episode, never the directory's popularity score, and a browse by category lists the most recently
updated first.

A question that names a podcast, an episode, a host or a co-host gets the `podcasts` field by itself,
the way a question about YouTube gets the `youtube` field: the workers read the person's own words in
the episodes before articles that quote them, one sub-question per show and topic, and every spoken
claim is cited by the episode and the time. `--field podcasts` asks for it on any question.

**The Podcast Index key.** The open directory, search by person and trending by category need a free
key. Sign up at https://api.podcastindex.org/signup with an email address and a line about what you
are building; the key and the secret come by email at once. Then:

```
researchzosho settings set podcastindex_key <key>
researchzosho settings set podcastindex_secret <secret>
```

or paste them into `researchzosho setup`, or on the Settings page, or tell the chat. The index's terms
forbid crawling it through the API; the library never does, every call is one lookup and a run makes a
handful at most.

**The Podcast Index as a file.** The index publishes its whole directory of shows once a week as one
file, for exactly the use the API forbids: searching and browsing the directory at large.

```
researchzosho podcast index download      # about 1.8 GB; unpacked and indexed, about 5 GB; a few minutes
researchzosho podcast index status
researchzosho podcast index remove
```

With the file on the machine, show search goes through it first, by word, with no call and no limit,
and runs can browse the directory by category and language, the most popular and recent shows first:
"Japanese shows about history, active this year" is one question. The housekeeping fetches each new
week's file while `RESEARCHZOSHO_PODCASTINDEX_LOCAL` is on, which the download turns on. The file holds
the shows only: episodes, people and transcripts still come from the feeds and the API. It serves every
library on the machine.

**Transcripts.** When a show publishes a transcript in its feed, the run reads that. When it does not,
the library transcribes the audio itself through the same server the video helper uses
(`researchzosho video install` starts one on this machine; `RESEARCHZOSHO_WHISPER` names one on another
machine, for instance a box with a GPU), keeps the transcript, and deletes the audio. A run may
transcribe two episodes or two hours of audio, whichever comes first; `RESEARCHZOSHO_TRANSCRIBE_EPISODES`
and `RESEARCHZOSHO_TRANSCRIBE_MINUTES` change that. A transcript the library made is machine-read, and
the report says so. Citations name the episode's page and the time.

**Following a show.**

```
researchzosho podcast search iaido                 # shows, with their feed addresses
researchzosho podcast follow https://…/feed.xml    # its new episodes arrive on the arrival table
researchzosho podcast list
researchzosho podcast unfollow <name or feed>
```

A followed show is a shelf like any subscription: each night the housekeeping lists its new episodes and
transcribes them, published transcript first, without a cap unless `RESEARCHZOSHO_TRANSCRIBE_FOLLOWED_MINUTES`
sets one. Over the nights the show's whole run becomes searchable in the library, by its words.

## 10d. Settings

Every setting the library has can be read and changed in three places, which all do the same thing:

```
researchzosho settings                       # every setting, what it is for, its value
researchzosho settings set whisper http://gpu-box:18890
researchzosho settings unset whisper
```

the Settings page of the web app, and the chat, in a sentence ("set the transcription server to
http://gpu-box:18890"). A key is shown only as set or not, never its value. Changing a setting needs
write access; the chat asks before it writes. A change takes effect at once, except the number of
workers, which takes effect at the service's next restart. The settings live in the library's config
file, which you may also edit by hand.

## 10d-2. Sources and keys

Some of what the library reads wants a key from an outside service: a Google search through Serper or SerpApi,
Firecrawl for pages built by scripts, RentCast for homes, the Census Bureau for a tract's figures, the YouTube Data
API for videos in date order when a video you remember is looked for, the European Patent Office for a class of
patents enumerated whole, Trove for Australian newspapers, and the rest. One list says, for each, what it adds, whether it is free or paid and what
the free allowance is, where a key comes from, the setting that holds it, this month's use from the tally, and
what the last check of the key said. The prices and allowances are read off the services' own pages, and each
line says the day it was read; a page the library could not read says so instead of a figure.

The list is in three places: the Keep page under Sources and keys; `researchzosho services` in a terminal, and
`researchzosho settings` which says the same under each key; and the chat, which answers "how do I get a RentCast
key" or "what does Serper cost" from it. `researchzosho services check`, the Check button on the Keep page and
`library_settings op=check` ask every service whose key is set whether the key is accepted and, where the service
says, what is left this month; the answer is kept and shown in the table until the next check, and the weekly
housekeeping asks again. A key the check refused is said at the top of the Keep page.

## 10e. What you own

The library can hold your own purchase records, and the research and the chat can then tell what you
already have. Nothing needs this: a library with no records answers every shopping question from the
open sources; with them it also knows what you own, what you bought it with, and what you kept.

```
researchzosho owned import ~/Downloads/Your\ Orders.zip    # the zip Amazon sends when you request your data
researchzosho owned import ~/Mail/Inbox                     # a mailbox file, a folder of them, a Maildir, or .eml files
researchzosho owned import ~/statement.csv --currency USD   # a bank or card statement
researchzosho owned find usb hub                            # what, when, where, how much
researchzosho owned find --year 2019 --shop amazon
researchzosho owned                                         # what the ledger holds, and where each file's records begin
researchzosho owned forget <file>                           # drop what one file added
```

The Amazon zip is the one you get from Account → Security and Privacy → Request your data; its order
history carries every order with the product's name and ASIN, so those lines are exact. From a mailbox
the library reads Amazon's, eBay's, PayPal's and 楽天市場's confirmations by their shape, and any other
shop's by the subject and the prices in the text, so those lines are marked as read from the mail and
may be off; a receipt for a ride or a meal becomes one line with its total; a shipped notice adds nothing
to an order already there; replies and mail you sent yourself are passed over. A statement gives where
and how much, never what. An import run twice adds nothing.

Where a file's records begin is the file's first record, which is not when the account was opened: a
shop's export may start years after your first order there, and a mailbox has what you kept. The ledger
says "records begin", never "you started buying".

**The recall watch.** Every night the housekeeping fetches the open product-safety feeds, the US CPSC and FDA, the EU
Safety Gate, the UK OPSS and the OECD's portal, keeps them as files under the state folder, and matches them against
what you own. A notice that may concern something in the ledger waits in the inbox and on the Owned page with how it
matched: a barcode (exact), the brand and a model number (high), or the maker named in the notice and the words of the
name (medium). Put one away when it is not yours or is dealt with. `researchzosho owned recalls` lists them,
`owned recalls check` reads the feeds now, `owned recalls dismiss <id>` puts one away; the setting `recalls off` stops
the nightly fetch. Canada's and Japan's recall sites have no machine feed and are not read.

The same from the Owned page of the web app, from the chat ("what did I buy from Amazon in 2019?",
"do I already have an HDMI cable?"), and for a program as `library_owned`. Importing needs the library's
owner, since the file is on the library's machine; reading follows the library's access like everything
else in it.

## 10f. Shopping

A question about what to buy makes the run a researcher for the purchase, not a ranker. The candidates come only from
what shops list right now in your market, each with its price, shop and the time the price was seen; each is checked
for its datasheet, its recall notices, its repairability and what owners say; the table judges fit against the need you
stated, never popularity, and keeps to six rows; star counts and badges are mentioned as cues, never counted. Sites
whose terms forbid machine reading (Amazon, Consumer Reports, Wirecutter, Which?, Stiftung Warentest, RTINGS, 価格.com
and a few more) are pointed at by their address, never read, in every run. A budget you name is the shop's own filter
(`price_max` and `price_min`, in the market's money), the rows within it come cheapest first rather than in the shop's
own order, and `page` reads on past the first rows, so the candidates are everything the shop lists for the words and
the money, never its best sellers; the report says of each candidate which checks were made and which were not.

The market is the country you buy in (the setting `market`, asked at setup): one market per run unless you name more.
For something used, vintage, handmade, out of print or at auction the run asks eBay with your keys and, for the
marketplaces that cannot be read (Mercari, メルカリ, ヤフオク!, ラクマ, 日本の古本屋, AbeBooks), gives you a search link each
with the words typed in; a watched search tells you when something new is listed.
The shops are behind your own keys, all free:

| setting | where to get it | what it gives |
|---|---|---|
| `yahoo_jp_client_id` | developer.yahoo.co.jp → アプリケーションの管理 → 新しいアプリケーションを開発 (a "Client ID") | Yahoo!ショッピング listings and prices (Japan) |
| `rakuten_app_id` + `rakuten_access_key` | webservice.rakuten.co.jp → アプリID発行 (both come together) | 楽天市場 listings and prices (Japan) |
| `icecat_user` | icecat.biz → Open Icecat, a free account; the setting is the user name | datasheets by brand and model, or barcode, for the brands that sponsor the open catalogue (Kingston, Philips, HP and others); a brand in the paid tier is reported as such |
| `reddit_client_id` + `reddit_client_secret` | a Reddit app of type script; Reddit approves new ones by ticket, and closes its public API in March 2027 | what owners wrote, through Reddit itself; without it the threads the web search finds are read from the Arctic Shift archive (§26c) |
| `reddit_archive` | `on` (the default) or `off` | whether Reddit threads are read through the Arctic Shift archive, a copy of Reddit minutes to hours behind; `off` points at Reddit and never reads it |
| `stackexchange_key` | stackapps.com → register an application (free); the setting is the key | raises the Stack Exchange API's allowance from 300 calls a day to 10,000, for `forums` and `forum_search` on The Workplace, Software Engineering, Stack Overflow and the other sites |
| `ebay_client_id` + `ebay_client_secret` | developer.ebay.com → a keyset (the sandbox one at once; production by a short application) | eBay listings and auctions, every market |

Without a key the run says what is missing and goes on with the open sources: the Open Facts databases for a barcode
and a brand, iFixit for repairability, the recall feeds the library keeps, and the web for shops to point at. Outside
Japan no shop answers without a key the library does not have yet, and the run points at the shop's own page instead.

```
researchzosho settings set yahoo_jp_client_id …      # or from the Settings page, or in the chat
researchzosho research "which USB-C hub for a laptop with one port, under 5000 yen"
```

## 10g. Books

The books field answers "what should I read" the way a librarian would: from the catalogues, with real books, a
link to each one's record, and a word on what each covers and who it is for. It never names a book from the model's
memory.

"Which book on X should I read first", "a novel like these two", "おすすめの本は" make the run a books run. The candidates
come from the catalogues, not from the run's memory: Open Library (by words, subject heading and author), NDL Search for
Japanese books (by words, 件名 and NDC class), and a work's own record or openBD's description and table of contents. A
title the catalogue did not return in the run is not named. The workers look past the bestsellers: subject headings,
reading lists, syllabi, society and library lists, the "further reading" sections of long reviews, each title then looked
up in the catalogue before it counts. A subject heading is read whole, page by page (`page=2`, `3`…), since the
catalogue's own order weighs editions, which is popularity. The table has eight rows at most and says what each book covers in your terms, who
it is for, the year and edition, the length, where it was found and the one reason it fits or falls short; ratings and
edition counts are a column of cues. No keys are needed; with a CiNii appid (the setting `cinii_appid`, from NII's free
developer registration) Japanese books are also found by 件名 and author across the university libraries, each with how
many libraries hold it.

In the chat, say what you are after and answer the questions it asks one at a time (the level, the language, what you
have read on it already); the run is filed with every constraint in words. After the report, "more like the third one,
shorter" is answered from the report's own table; a narrow follow-up run only for what the run never looked at.

**Your own shelf.** Set `calibre` to your Calibre library (its folder or its metadata.db); it is read, never changed.

```
researchzosho settings set calibre "~/Calibre Library"
researchzosho myshelf                      # the profile: what you rate highly, as a subject mix, authors, decades, sample titles
researchzosho myshelf have "Anathem"       # already on the shelf, in the purchase ledger or on a holdings list?
researchzosho myshelf liked space opera    # what you rated on a subject, best first
researchzosho myshelf refresh --minutes 5  # read the catalogues of the authors you rate well now, instead of waiting for the night
researchzosho myshelf dna "Anathem"        # a book's DNA: the text counted, and the model's reading of it over twelve windows
researchzosho myshelf dna --minutes 60     # read the shelf for its DNA now, your picks first (the night does 20 minutes)
researchzosho myshelf like "Anathem"       # the books on your shelf like it, by their DNA; the author's own works set aside
researchzosho prefs                        # your preferences file, every line with where it came from
researchzosho prefs draft                  # a file drafted from your shelf
researchzosho prefs set prefer.mood gentle # a line of your own (weight.subject 2, roam 0.4, popularity none, words "…", exclude.author "…", avoid.kind business)
researchzosho react like "Anathem" --about "Snow Crash"   # a reaction; --about names the book whose "like" list it was on
researchzosho myshelf browse               # a spread of your labelled books to browse; react, and the next list moves
researchzosho dna status                   # the DNA store: how many documents, how many labelled, whether you share
researchzosho dna records --minutes 10     # the catalogue's subjects, editions and ratings joined to your books' documents now
researchzosho dna pack export ~/books.jsonl.gz   # a pack of the labelled documents, the shared pool's format
researchzosho podcast like "harbour hour"  # the episodes like one, among the transcripts the library holds
```

The profile is the books you added yourself. A day on which fifty or more books arrived is an import that came with the
files, and those books count as holdings, never as taste; a book added on an ordinary day is one you chose. A star
counts only when you gave it: a day with fifty or more ratings is an import too, and its stars are set aside (one shelf
of 74,707 books held 3,462 stars, 3,410 of them from four such days, none given by its owner). The profile says how
many books and ratings are yours and how many came with the files. It also lists the podcasts you follow, the searches
you watch and the shops you buy from. A books run reads
it as the `my_shelf` tool, checks every candidate against it and does not recommend what you have; the chat and MCP have it
as `library_my_shelf`. A "what should I read next" run starts from the works of the authors you rate well that you do not
have, then from the catalogue under the subjects you rated highly for breadth, what you own left out and each candidate's
fit counted. The night housekeeping keeps the catalogue of each author you rate well on this machine, two minutes a night
until all are on file and then as they go stale, so the run reads them without waiting. Measured on a shelf of 2,134 rated
books: the authors reach a third of what the person went on to like, the subjects a sixteenth. With no library set, the
run goes on from what you asked for.

**A book's DNA.** For every book with an EPUB on the shelf the library keeps a card, its DNA, in three layers: what the
catalogues say of it, what a program counts in the text, and what the model reads in it. The record: what the shelf and the
catalogues say, each with its source. The measured: what a program counts from the whole text — length and chapters,
sentence length and spread, dialogue share, vocabulary, a reading grade, person and tense, and the arc of the words'
feeling through the book. The labelled: the model's reading of an excerpt (kind, audience, demand, themes, setting) and of
twelve windows of 1,500 words spread over the text, each judged against an ordinary book of its kind — for fiction the
readers'-advisory appeal terms (menacing, suspenseful, heart-warming, bleak, bittersweet, gentle, darkly humorous, lyrical,
gritty, whimsical, haunting, upbeat, sardonic, nostalgic, violent, romantic), for nonfiction tone words (dry, wry, polemical,
instructional, conversational, earnest, playful, urgent, reflective, technical, warm, scholarly, indignant, hopeful, bleak,
intimate) — with pace, darkness, scale, humour, style, drive and two to four topics a window. The windows become the book's
labels (each mood's share of windows; the scales' mean, spread and arc; the agreement between windows as the confidence,
never the model's own word) and its signature (for every mood, topic and value: how often, where in the book, whether it
grows; how the book moves). `researchzosho myshelf dna "<title>"` shows the card and makes the DNA when it is missing (the
model takes about a minute a book). The night housekeeping reads the shelf for it, the books you went for first, for
`dna_minutes` a night (20 unless set; 0 turns it off; `researchzosho tonight` says how long the next night will spend on it);
`myshelf dna --minutes 60` reads now, at most 240 minutes a call. The documents are under
`~/.researchzosho/dna`, one per book, keyed by the file's hash, and hold nothing of your stars or reactions: they describe
the book, not you. The chat and MCP have the card as `library_my_shelf` op=dna. The record layer is filled from Open Library
too — the subject headings, the work key, the edition and rating counts, the first year, found by ISBN first and then by title
and author, each said to come from the catalogue — the labelled books first, two minutes a night, keyless (`dna records
--minutes 10` does it now; a book the catalogue does not know is asked again after a month). A shelf's own tags are shop
codes and "General"; the catalogue's subjects are what "like X" compares first.

**"Like X".** `researchzosho myshelf like "<title>"` lists the books on your shelf like one by what the texts say: subject
first (the themes and topics the model read and the subjects the shelf and the catalogues give, kept as phrases — "artificial
intelligence" is one subject, not two words — and the index, notes and bibliography at the back of a book are no part of its
reading), then kind, audience and demand, then where and when the book is set (from the catalogue and from the model's
reading), then mood and pace at a small weight. The measured form is left out of it: among books already alike in subject,
closer in form read as *less* alike to the judge, so form serves "written like X" and not this. Every row says on which
dimensions it is alike and on which not. Books of the same kind come first; a book of another kind is listed only when it
shares a subject, never for its mood alone, so a list ends short rather than wander. At most two books by one author stand in
a list, and the rest are set aside under the author's name; the same author's and the same series' books as the one you named
are set aside into a side list — "more by the same author" is the old way of finding more, not a likeness. One well-known
title stands in every list, so you can say whether you want more or fewer of those. The list is only as wide as the labelled store: the night housekeeping labels about one book a
minute, your picks first. A research run has it as `my_shelf kind=like`, and a question that names a book on your shelf
("something like The Harbour Ledger") has the list pushed in before the workers start.

**Your preferences file.** Taste lives in `~/.researchzosho/prefs/books.json`, never in a book's document. `researchzosho prefs`
shows it, every line with where it came from: the weights "like X" uses (subject 2, kind, audience and demand ½, mood ½, scale
¼, form 0), what you prefer, avoid and leave out, the roam knob (0 keeps to the nearest, 1 wanders far), the popularity line
(one well-known title in every list, none, or well-known preferred) and a line in your own words. `prefs draft` writes one from
your shelf — the subjects you went for most, and from the labelled books among them the moods that recur, the kind that
dominates and a pace when it leans one way, each line saying how many books it rests on. `prefs set <name> <value>` changes a
line, `prefs unset` drops one, `prefs export <file>` and `prefs import <file>` carry it. The same ask under two preferences files
gives lists that differ in the stated direction, which is the test of it.

**Reactions.** `researchzosho react like "<title>"` (or dislike, seen, own) writes a reaction down with a snapshot of the
book's DNA; `--about "<X>"` says it was a row of X's "like" list. A disliked book leaves later lists, a seen or owned one is
marked, and the preferences file learns from the likes and dislikes: a mood or subject in three liked books is preferred, a
kind disliked three times and never liked is avoided. The reactions given on rows of "like" lists are the judged pairs that will
one day learn the weights — not before there are hundreds of them across dozens of books.

**Browsing.** `researchzosho myshelf browse` is for "I'll know it when I see it": a spread of your labelled books across kind,
mood, pace, scale and subject, each row as different from the last as the store allows, your preferences file as the prior (a
prefer line lifts a book, an avoid line lowers it), one well-known title in, and what you reacted to left out. Each row says in
a sentence what the book is. React to any, and the next list moves; `--seed N` brings the same list back.

**The shared pool, and the pack.** The documents describe books, not you, so they can be handed on. `researchzosho dna pack
export <file>` writes a pack of the labelled ones: the first line says the format (`rz-dna-pack-1`), the kind, when, the layers'
versions and the count; then one document a line with the record, the measured and the labelled layers and a provenance line —
no shelf ids, file paths, stars or reactions. `dna pack import <file>` reads one in: documents this machine lacks are added, its
own are kept, one of another prompt version is set aside. `dna pack refresh` fetches the pack researchzosho.org announces when
there is one (none is published yet; the format is ready for it). Contribution is opt-in: with the setting `dna_share` on, the
night writes your pack to `~/.researchzosho/dna/outbox` once a week for you to send, named by `dna_contributor` or anonymous.

**Podcasts, the second kind.** An episode whose transcript the library holds gets its DNA the same way: the record from the
capture (the show, the episode, its date, its length from the last time stamp), the measured layer from the spoken words with the
words a minute, the labelled layer over windows of the talk by the same questions. `researchzosho podcast like <words>` lists the
episodes like one, the same show's other episodes set aside — more of the same show is the old way of finding more, as an
author's other books are. `podcast dna --minutes 10` reads the transcripts now; the night spends a quarter of `dna_minutes` on
them after the books. The chat and MCP have it as `library_dna`.

## 11. The housekeeping

The housekeeping runs at three each morning, or when you run `researchzosho crews`. Its steps:

| Step | What it does |
|---|---|
| serials | Runs the searches you keep (`researchzosho shelf add <name> <query>`) and lists anything new. Lists reviews that are overdue. |
| explorer | Researches a few of the open questions. |
| review | Reads new write-ups and extracts the claims, each with its source, as drafts for your inbox. |
| catalog | Files claims under subjects from your list, up to 200 a night. When none fits, it proposes a new subject, and the claim is not asked about again until it or the list changes. Family claims are not filed under subjects: they are organised by person and relation. |
| triples | Records the connections between people, places and things that claims describe, for the map. |
| inventory | Re-reads a few accepted claims against their sources and disputes any the source does not support. |
| preprints | Checks for newer versions of preprints you cite and notes them on the claims. |
| retractions | Checks every cited DOI against Retraction Watch. |
| abstracts | Rewrites the summary of any subject whose claims changed. |
| graph | Notices when two entries look like the same person or place and writes a proposal. |
| refresh | Updates the search index. On the first of the month it rebuilds the index. |
| backup | Keeps a dated copy of the library. One week of copies is kept. |

On Sundays there are two more steps. One lists accepted claims that look like duplicates. The other
lists documents older than a month that nothing refers to. The housekeeping never merges, deletes or
decides. `researchzosho raw prune` deletes the documents on the second list, when you run it.

### What will run tonight

Two steps do research unattended. The serials step re-runs the searches you keep, on the schedule you
gave each one. The explorer step takes open questions from a queue. Nothing else searches the web at
night.

Every open question in the queue has a type, a place in the order, and a state. The types:

- `report`: a run left it open.
- `asked`: the library could not answer it at the desk. It counts how often this happened.
- `person`: you added it.
- `dispute`: what would settle a disputed claim.
- `check`: a re-read found the source does not support a claim. This is a chore for the Inbox and is
  never researched.

The explorer takes `report`, `asked` and `person`. An `asked` question is taken once it has been asked
twice; `RESEARCHZOSHO_EXPLORER_MIN_ASKS` sets that count. `RESEARCHZOSHO_EXPLORER_TYPES` sets which
types it takes. A parked question stays on the list but is not taken until you unpark it. The
questions a report leaves open are filed parked. A report leaves five to ten of them, one per
perspective, and nothing runs on them until you look. `RESEARCHZOSHO_REPORT_QUESTIONS=queued` files
them straight into the queue instead.

The explorer takes the queue in order. It does `RESEARCHZOSHO_EXPLORER_PER_NIGHT` runs a night. The
default is 2, and 0 turns it off. `researchzosho questions budget <n>` changes that. `questions
tonight <n>` changes it for one night only. Related questions share a run. The questions one report
left open, or questions whose words overlap, become one run's sub-questions, up to eight. So one run
answers several questions. `RESEARCHZOSHO_EXPLORER_MINUTES` caps each run's minutes. There is no cap
by default.

To see the plan before it runs:

```
researchzosho tonight
```

It lists the searches due tonight and the ones not yet due. It lists the open questions the explorer
will take and how many wait. It shows how many accepted claims will be re-read, and the weekly or
monthly extras. The Runs page shows the same. `researchzosho crews` runs the housekeeping now.

### Sorting a long list of open questions

A few reports leave dozens of questions. The Open questions page, `researchzosho questions list`,
and `library_frontier` sort them the same eight ways:

| filter | what it does |
|---|---|
| left by | the report that left the question; the page groups by report, and one box ticks the whole group |
| what became of it | whether that report was kept (a claim of it accepted), is still in the inbox, was disputed, or was retired |
| asked from | the perspective the planner asked it from, the `[Historian of Science]` tag |
| subject | the subjects of the report's claims, and any subject named in the question |
| reads alike | questions whose words overlap fold under the first of them; open the fold to see them |
| maybe answered already | a claim on the shelves that already answers the question, found by search |
| language | the language the question is in, or asks for ("in Japanese-language sources") |
| words | words that must all appear in the question |

On the page, each filter is a row of links with counts. The counts say what a click would show. On
the command line: `questions list --report I-0016 --fate kept --who historian --subject vae --language japanese --grep "lead paint" --by-report --hints`.
`--hints` runs the search. `--parked` shows parked questions. `--all` shows everything. Over MCP or
HTTP, `library_frontier` takes the same names: `report`, `fate`, `who`, `subject`, `language`, `q`,
`show`, `hints`.

Before 0.1.2, a report's questions were filed twice, once by the run and once by the review. The page
says when it finds second copies. `researchzosho questions tidy` deletes them.

`researchzosho questions park <n> --why "<reason>"` parks a question and keeps the reason with it, for
example "waits on the archive's answer". `questions list --parked` and the Open questions page show the
reason with the date it was parked. Over MCP or HTTP, `library_frontier` op=park takes `why`, and
op=list returns it as `parked_why`. The reason goes when the question goes back in the queue, is dropped,
researched or taken off, or is filed again. Parking a question that is already parked says so, and a
`--why` given then replaces its reason; over MCP or HTTP the answer carries `already: true`.

### Setting and unsetting what runs on its own

There are two kinds: a kept search, and an open question. Each can be set or unset four ways.

| | a kept search | an open question |
|---|---|---|
| command line | `researchzosho shelf add <name> <query> [days]` · `shelf list` · `shelf every <name> <days>` · `shelf park <name>` · `shelf unpark <name>` · `shelf remove <name>` | `researchzosho questions list [filters]` (numbered, tonight's marked) · `questions add <question>` · `questions next|later|park|unpark|drop <number>` · `questions tidy` · `questions budget <n>` · `questions tonight <n>` |
| web pages | the Open questions page: "Keep a search"; beside each one, its every-N-days field with "Change", "Park" or "Back in the rotation", and "Stop keeping it" | the same page: the eight filters above, grouped by report; tick any number, then "Send as runs now", "Run next", "Later", "Park" or "Back in the queue", "Drop"; the nightly budget and tonight's override; "Add an open question" |
| a program (MCP or HTTP) | `library_serials` with `op` list, add `{name, query, every_days}`, every `{name, every_days}`, park or unpark `{name}`, or remove `{name}` | `library_frontier` with `op` list (with the filters; each question carries type, parked, position, tonight, report, report_fate, perspective, subjects, language, similar), add, next, later, park, unpark, drop `{question}`, or tidy |
| the files | `catalog/shelves.md` in the library folder, one line each: `- name \| query \| every N days \| last YYYY-MM-DD`, with `\| parked` at the end to park one | `frontier/OPEN.md`, one line each: `- YYYY-MM-DD [kind] question`; a line ending `⇒ explored …` is closed |

A parked search is kept and shown but does not run until you put it back. Parking a question works
the same way.

The files are plain markdown. Edit them in any editor and the next housekeeping reads them. The vault
has a `Housekeeping` note that shows both lists and says where the files are, so an Obsidian or
SoloMD user can find them. The vault itself is a view. Editing it changes nothing. A dropped question
stays in the file, marked dropped, so it is not filed again.

The review, catalog, triples and abstracts steps also run as soon as a write-up arrives, so you do not
have to wait for the night. Filing under subjects then takes only that write-up's own claims; the rest
of the library is the night's. `researchzosho settle` runs them by hand for any write-ups still waiting.

### Proposed subjects

```
researchzosho subjects proposed          # the proposals, numbered
researchzosho subjects accept 3 7        # accept by number or by slug; "all" accepts every one
researchzosho subjects drop 12           # "all" clears the list
researchzosho settle                     # then file the waiting claims under the new subjects
researchzosho catalog seed               # start a list from the claims: the subjects two or more claims were given
researchzosho catalog seed --min 5       # only the subjects five or more claims were given
```

You can also edit `catalog/subjects.md` directly. A library that has never had a subject list files
nothing under subjects: the model can only propose. `catalog seed` asks which subjects fit each claim
once, adds the ones several claims share, and files the claims under them.

Everything the housekeeping does is logged in `catalog/crews.log`. Steps that need a model are
skipped when no model is available. When a write-up arrives with no claims, or fewer than you
expected, the `review` lines there say why. Either the extraction was cut off, or it could not be
read, or each claim had one web source and waits in the Inbox for you. The `settle` line that follows
counts those problems.

## 12. Sharing the model

Research runs use the same model, and usually the same graphics card, as everything else on your
computer. You can change these settings while a run is in progress:

```
researchzosho research workers 2              # how many readers work at once (unset: up to 3, see below)
researchzosho research workers auto           # back to the rule
researchzosho research pause                  # hold everything at the next step
researchzosho research resume
researchzosho research stop J-0031             # end one run within seconds; a queued one never starts
researchzosho research window 22:00-07:00     # only start runs in these hours
researchzosho research window off
researchzosho research                        # show the settings and what is running
```

How many runs go at once, and how many readers each run uses, follow the model server unless you set
them. A llama.cpp server says how many calls it answers at once (its slots); Ollama is taken as one
and vLLM as eight. One run gets up to three readers, and there is one run per three slots, at least
one of each: four slots mean one run at a time with three readers, six slots two runs. With several
servers in `job.drives` (`researchzosho settings set job_drives <address>,<address>`), the slots add
up and each server carries one run per three of its own: a server with eight slots and one with a
single slot make three runs, two on the first and one on the second, and a run's readers follow
its own server. `researchzosho status` says what is in use and why. Set `job.workers` for the runs (it takes effect at the next
restart) and `research.workers` for the readers (a running run follows at its next turn) to choose
your own numbers. A second request does not fail: it waits in the queue, and the pages say how many
are ahead of it.

The Runs page has the same controls: "Pause the runner" and "Resume", and a "Stop" beside each run
that is queued or running. A stop ends a run within seconds, whatever it is doing, also when it is
waiting for the model, a web page or a search: what it waits for is given up and its connection
closed. The run is recorded as stopped, not failed, and nothing of it is filed, also when the stop
comes after its research and before its report is filed. If you stop a run once its report is being
filed, the report stays in the library, and `research stop` says so: the checking of the report's
claims that comes after the filing ends with the step it is on, and the nightly housekeeping finishes
it. The nightly tasks end after the task they are on. Over MCP or HTTP, `library_job` takes `op` stop
with `job_id`, pause, or resume.

A request to the model may take, from the question to the last word of the answer, as long as reading
its prompt and writing its answer take at the slowest pace the model keeps, and at least five minutes
(`RESEARCHZOSHO_DRIVE_TIMEOUT`, in seconds, for a slow model). The pace is half the median of the
model's tokens a second over its last twenty answers, and ten tokens a second until three answers were
measured. The log gives the limit of each request. A request that takes longer is given up and its
connection closed, the log says so, and it is not asked again with the same limit; a request that
never got through is asked once more. A page is read
for as long as it keeps coming: the fetch gives up when nothing has come for as long as it waits for the
site to answer (30 seconds for a page a run reads, 60 for one you add), or when the page comes slower
than 4,096 bytes a second on average (`RESEARCHZOSHO_FETCH_MIN_BYTES_PER_SECOND`), so a large scan from a
slow archive is read to its end. A search keeps to its limit for the whole answer.

The settings are stored in your config file. A running question follows a changed setting at its next
step.

With no setting, the readers slow down when the model drops to half its usual speed because something
else is using the card.

### Letting the model server sleep

The model does not have to be up all the time. The library works with a server that comes and goes:

- Searching, reading and the pages never call the model.
- A research run waits and asks again every 30 seconds until the model answers. While it waits, its
  record and the Runs page say "waiting for the model".
- A run whose model server goes away in the middle, while it restarts or loads its model, waits for it
  and goes on when it answers, within the run's time limit. Its log says how long it waited. A server
  that freezes or restarts while a request is open is noticed within a few minutes: while a request
  waits, the library asks the server whether it is working on anything, and gives the request up and
  asks again when it is not.
- A run sends the server no more requests at a time than the server takes at once (llama.cpp reports
  it), so on a server shared with other programs their requests are answered between the library's.
- The nightly housekeeping skips the steps that need the model.
- sharpen, perspectives and explain tell you at once.

When setup finds no server, it offers to serve the model on demand. `researchzosho model install`
does the same later, on all three platforms. It first looks for a server another program already runs
on this machine (Wyrdsekai's on port 8200, llama.cpp on 8080, vLLM on 8000, Ollama on 11434, LM Studio
on 1234). When one serves a model from the table in [MODELS.md](MODELS.md), the library uses that server and
downloads nothing, so the model is in memory once for every program. `model install --own` installs
the library's own model even then, and `--file` or a model named to `model switch` always do.

```
researchzosho model install                 # the measured model for this machine, downloaded once, served on demand
researchzosho model status                  # the proxy, the model, the embeddings server, whether the memory is in use right now
researchzosho model stop                    # unload now; the next request starts it again
researchzosho model uninstall               # remove the service; the model files in ~/models stay; the drive goes back
researchzosho model check                   # every model file and pinned build still resolves where the rows say
researchzosho model use http://192.168.1.20:8080 qwen3.8-27b   # use another model server, now
```

`model use` asks the server what it serves and asks the model to reply before it changes anything. The
running service takes the new model for its next research run and its next question, with no restart;
a run going at that moment finishes on the model it started with. With one model on the server, you can
leave out its name; with several, `model use` lists them.

The embeddings server runs beside the model at the same address, under the model name `embed`. It is
in its own group, so neither evicts the other. It never idles out, because it is small and every
search uses it. `RESEARCHZOSHO_EMBED` is set to the proxy too. Uninstall restores both settings.

Every download is checked against a recorded sha256 and refused on a mismatch, like the one-line
installer does. Uninstall refuses while CodeZaiku's settings still point at the proxy, unless you pass
`--force`.

It installs a small proxy, [llama-swap](https://github.com/mostlygeek/llama-swap), as a service that
starts with your session on port 8211. llama.cpp runs behind it. The first request starts the server.
20 idle minutes after the last request, it stops, so the memory is free in between. The drive is set
to `http://127.0.0.1:8211`. What runs behind the proxy depends on the machine:

| platform | the server | the service | sized by |
|---|---|---|---|
| Linux with an NVIDIA card and Docker | llama.cpp's CUDA container | a `systemd --user` unit | the card's memory |
| Linux with an AMD or Intel graphics chip and Docker | llama.cpp's ROCm container on an Instinct card; its Vulkan container on a Radeon, a Ryzen AI or an Intel chip | a `systemd --user` unit | what the BIOS set aside for the chip; up to 8 GB for Intel |
| macOS | llama.cpp's own Metal build | a launchd agent | seven tenths of unified memory |
| Windows x64 | llama.cpp's own Vulkan build, any card | a logon task | the NVIDIA card's memory, else half the RAM |

- `--idle-minutes` changes the wait.
- `--gpu <index>` picks a card on a Linux machine with several.
- `--file <gguf>` serves a model file you already have.
- `--share` makes the proxy answer on every interface, so other machines can use this one.

If the machine already has a proxy on 8211, from CodeZaiku's `model serve install` or from an earlier
setup, it is used unchanged. Nothing is installed twice.

Every program points at the proxy, never at a server directly. So moving the model to another machine
means changing one address in each program's settings. Install on the machine with the memory, with
`--share`. Set `drive = http://<that machine>:8211` everywhere else. On a 27B at 4-bit on an RTX 6000
Ada: the model list answers at once, the first request after a quiet spell answers in about 25 seconds
including the load, the next in half a second, and the card is empty again 20 minutes after the last
request. Ollama does the same by itself, with `OLLAMA_KEEP_ALIVE`. The numbers in this guide are for
llama.cpp.

### Which model

`researchzosho models` prints the measured choice of model for the card it finds, with the command
that serves it. The full list by VRAM is in [MODELS.md](MODELS.md). VRAM is the graphics card's
memory, not the computer's RAM.

### Two models

`RESEARCHZOSHO_JUDGE_DRIVE` names a second model server for planning, the critic, the write-up and the
citation check. The readers keep using the first server. So a stronger rented model can do the judging
while a local model does the reading.

### What a write-up carries besides the answer

After the answer, every write-up has a few sections the library adds itself. The model does not write
these.

- **References.** Every source the readers used, numbered. When the writer cites a source it puts the number
  after the sentence, like `[3]`. Copies of one text count as one source. A source that only repeats another
  is marked "cites [n]", so you can see how many independent sources there are.
- **Evidence.** Every note a reader took: the claim, the source, and a short quote.
- **Cite-check.** Every cited sentence, read against the source it cites. A sentence its source does not
  support is marked in place: `[not supported by the cited source on check]`. The check is mechanical
  first. When the sentence's numbers, or a run of its words, appear in the source, that settles it. The
  rest goes to the model. Before a sentence is marked, the model has to quote the passage from the source
  that would support it. If the quote is in the source, the sentence is not marked.
- **Checks.** This section appears only when there is something to report. It lists numbers, licence names
  and CVE ids in the answer that no note or source backs, quotations that appear in no source, and any
  cited paper that Crossref lists as retracted. When the report waits for you and is not accepted, the
  section opens with the reason, one sentence each.

Read the Checks section before the prose. When something looks wrong, the Evidence table shows what
the readers saw.

### What the runs cost

```
researchzosho stats            # the last 20 research runs and the totals
researchzosho stats 100        # the last 100
```

Every run adds one line to `catalog/runs.jsonl` when its write-up is filed. The line has: turns,
rounds, whether the critic was satisfied on the first pass, whether a time or turn limit cut it short,
how many cited sentences were checked and how many held, sources noted, fetches in total and how many
were pages another reader had already fetched, tokens and minutes. `stats` prints those lines and
their totals. When you change a setting, this is where you see whether it changed anything.

Every run also writes a trace to `catalog/traces/<job>.jsonl`. It has one line for every call to the
model: the exact request, the tools offered, the shape of the reply, the server's token counts and
how long it took. It has one line for every time evidence was cut to fit the model's window. Each
call's `seat` names the stage of the run it belongs to (plan, workers, critic, write-up, topic-check,
analysis, cite-check, revise), and the first call of each stage keeps what the model thought before
it answered, when the model server returns it. The newest fifty traces are kept. Open this file when
a write-up says something its evidence does not. `RESEARCHZOSHO_TRACE=off` turns tracing off.

## 12b. Talking to the Librarian

```
researchzosho chat                 # continue the last conversation, or start one
researchzosho chat --new           # start a fresh one
researchzosho chat --sessions      # list earlier conversations
researchzosho chat --resume C-…    # reopen one
```

Type a question. The Librarian looks it up and answers with the entry ids, like `[F-0012-a]`, so you can
open them. Things you can say:

- "what do the shelves hold on X?": the answer, with sources and states.
- "why?": it takes that as a question about the last answer.
- "find out how the gears were cut": files a research run. "yes" is enough when it offers one.
- "find out who my great-grandfather's parents were": the Librarian files the run and asks, under its
  reply, whether to use genealogy mode for it, ending with `(y/N)`. `y` or `yes` (or はい, ja, oui) says
  yes. Enter, `n`, or anything else says no, and the run is ordinary research. With no answer within 10
  minutes, the run starts as ordinary research, and what you type after that is a new message: the
  Librarian says the run started and answers it. The question is not asked again in the same
  conversation. The web Chat page shows a Yes and a No button while the question waits. A family word in
  what you say is asked about only for the run it is about, so a second, unrelated run in the same reply
  starts at once.
- "how did that go?": the answer section and the checks from the finished run.
- "what's waiting?": the inbox. "accept the first" accepts it.

**A run's own settings, in words.** When you ask for a run in the chat, you can set it in the same
sentence, the way the command's flags do: a time limit ("don't exceed 30 minutes", "within half an hour",
"take up to 2 hours"; 30分以内, 2時間まで), a limit in model steps ("in at most 20 turns"; 20ターン以内),
where it reads ("from the shelves only", "without the web", "search the web too"; 棚だけから, ウェブなし,
ウェブも), no paid search service for this run ("use no paid services for this run"; 有料サービスは使わない),
and the model it runs on ("with deepseek", "use the big model for this"; deepseekで). The Librarian says
back what it read, under its reply: "Research run J-0012, as you asked: up to 30 minutes, from the shelves
only." The run's record carries it, and the report's first lines say whether the run finished within its
limits. A phrase that is not clearly a setting stays part of the question: "why does an eclipse take 30
minutes" sets no limit. "In Japanese", "a short report" and "APA citations" work as before.

**The models, in words.** A question about the models the library runs on is answered by the library itself, from its
own list and settings, with no model asked, so the answer is the state as it stands: "which model is the default?",
"which models do we have?", "is kimi set up?", "does deepseek have a key?", "why did J-0309 use openrouter?" (the
run's own record: the entry and address it ran on, and when it failed, the error). The reply names the default entry
with its model, host and address, then the other entries one a line, each saying whether a key is kept or none is
needed. "Make deepseek the default", "use kimi as the default", "switch the default to deepseek" and "the default
should be deepseek" change the default for every run and chat that names no model, the same change as
`researchzosho model use` and the Settings page, and the reply says what it was before; a reader is told it is the
keeper's to change. A name that matches no entry, or two of them, changes nothing: the library lists the entries that
could be meant, with their addresses, and asks which. `/model <name>` switches this chat alone. In Japanese:
既定のモデルはどれ？, どのモデルを使っていますか, deepseekを既定にして. The notice for a run that failed carries the
entry it ran on and the error's first line, so nothing has to be guessed.

If the library has nothing, it says "I don't know" and offers to find out.

It follows two rules. It uses only the library's own tools: no commands, no reading your files, no web
outside a research run. And it does not make things up. A figure or a source that none of its look-ups
returned is marked under the reply as its guess.

Inside a conversation, at the terminal and on the Chat page alike: `/new`, `/sessions`, `/resume <id>`, `/runs`,
`/model`, `/share`, `/sources`, `/interview <name>`, `/suggest`, `/help`, and each field's own command (`/family`,
`/career`, `/home`, `/patents`, `/video`); `/quit` leaves the terminal. On the page, `/new` and `/resume` open the
conversation they name. Conversations are saved in `catalog/chat/` and continue where they left off.

**Conversations.** The chat is mostly short: a question or two, a research run set up and read. A conversation that has
been quiet for a day is not continued: your next message opens a new one, and the reply begins with a line that says
so and names the earlier conversation, which stays on the list under Conversations. The New conversation button
beside the box (or `/new` at the terminal) starts one whenever you like, and when a run this conversation filed has
its report on the shelves, the notice suggests it. The library never splits a conversation on a change of subject;
that is yours to decide. The model sees a conversation's last 12 turns, a short summary of what came before (the
earlier asks' first words, the runs this conversation filed and the reports they left, the files attached) and the
shelves as they are today; the conversation file keeps every turn, and `/resume` opens an old one whole.

The library answers these itself too, without the model, in both chats: `/explain <id or a term>`,
`/perspectives <question>`, `/established <claim>`, `/read <id or address> [--in ja]`, `/subjects` (with `proposed`,
`accept` and `drop`), `/serials` (the saved searches: `add`, `remove`, `park`, `unpark`, `every`),
`/export <id or subject> [--pdf|--docx|--md]`, `/remove <id>` (shown first, then `--yes`), `/story <subject>`,
`/forecasts`, `/forecast <P-…> happened|did-not|void`, `/dna`, `/prefs`, `/react like|dislike|seen|own <title>`; and
for the person who keeps the library, `/mcp`, `/readers`, `/invite <name>`, `/keys`, `/crews tonight|now`,
`/tonight`, `/spend` and `/update`, which says what an update would do and the command that does it. Saying the same
thing in words works where the model has the tool: explain, perspectives, established, the saved searches and an
export are on its table.

The same conversation is on the pages at `http://127.0.0.1:4649/chat`. A reply takes as long as the
model takes. The page returns when the reply is ready. Someone who signs in there and may only read has
conversations of their own, kept apart from yours, and never sees yours.

**Research runs started from the chat.** The chat follows every run it starts. You do not have to ask
how it is going.

- When a run moves to a new stage, the terminal chat prints one line: the stage, the time elapsed,
  and about how far along it is. For example: `J-0012 · Reading, 3 of 8 parts done · 12 min elapsed
  · about 40% done`.
- When the run is done, the chat prints one notice with the report's id. Say "show it" to read the
  answer.
- `/runs` shows the followed runs at any time.
- If you close the chat first, the notice is waiting when you resume the conversation.
- The web Chat page shows the same line for each run, with a progress bar, above the input box. It
  refreshes in place.

The stages are: Planning, Reading, Checking what is still missing, Writing the report, Checking
citations, Saving the report and its claims. The percent is the larger of two numbers. One comes from
the stage. The other comes from the clock: against the run's time limit when it has one, otherwise
against how long research runs usually take in this library. It never reads 100 until the run is
done. The terminal chat prints the line on every stage change and every five minutes.

**Keys in the terminal chat.** The up and down arrows go through what you typed before, in this and
earlier sessions. Ctrl-R searches it. Ctrl-C clears the line. Ctrl-D leaves. The history is kept in
the library under `catalog/chat/history`.


### Asking it about itself

The library knows what it is. It reads its own documentation, this guide among it, for the version that
is running: a copy installed from a release fetches it from GitHub the first time it is needed and keeps
it, so it works offline after that, and a copy run from the source tree reads the tree's own. It also
reads its own state when you ask: the version running, what the library holds, the model it talks to and
whether that model answers, the web search it has, the fields that are on, the chat's commands and the
pages.
Ask in the chat, in your own words: "which version is running?", "what can't you do?", "how do I add
my bookmarks?", "why do my runs find so little?". It answers from those, and when its documentation
does not cover something it says so and gives the address to ask at.

`/help` in either chat lists the commands. `/help` and some words looks them up in the documentation,
without the model:

```
/help how do I update
```

The Help page does the same and opens each document whole. A program that uses the library asks it
with `library_help`. The fields' own commands, such as `/family`, work in the browser chat as at the
terminal; there, the family commands that change the family's facts are for the library's owner.

## 12c. Bridges: questions that connect two subjects

`researchzosho bridges` finds two subjects that share a concept but that no source connects. It writes
a research question about them. A research run tests the question.

```
researchzosho bridges --dry                    # from the busiest areas: show the pairs, file nothing
researchzosho bridges speech--wav2vec2 --dry   # from one area
researchzosho bridges speech--wav2vec2 --reach high --loose
```

An area is a subject in your catalog with at least two findings. For a pair of areas, a bridge is a
specific term that both areas' claims use, while no source on the shelves names the two areas
together. The classic example is fish oil and Raynaud's disease, joined through blood viscosity and
platelet aggregation. The two literatures never cited each other. The pairs are found mechanically and
ranked. The model reads only the top pairs and writes each as a question, never a claim: "does the
platelet effect that fish oil has bear on Raynaud's, as viscosity links them?"

Without `--dry`, the questions are filed on the open questions as proposals of type `bridge`. The
housekeeping does not research them by itself. You decide:

```
researchzosho bridges list
researchzosho bridges accept <question>      # files the research run that tests it
researchzosho bridges dismiss <question>
researchzosho bridges measure                # proposed, kept, dismissed, and by which settings
```

Two sensors look for the join. `--via terms|graph|both` picks one, and `both` is the default. The term
sensor looks for a word both areas' claims are about. The graph sensor walks the library's map. It
starts from what one area's claims are about, follows the claims, and reaches what the other area's
claims are about, through concepts in between. "Fish oil lowers blood viscosity" and "Raynaud's
involves blood viscosity" meet at the middle node even when the two sides share no word. Nodes whose
names mean the same are walked as one node. The graph sensor walks two kinds of edge: the triples,
which are what a claim asserts, and the concepts each claim uses. The housekeeping's `concepts` step
notes those concepts on every finding, and the map draws them as "mentions" edges. Both are added
nightly. `researchzosho triples` and `researchzosho concepts` add them now. Each proposal says which
sensor found it, and `measure` keeps the score per sensor.

To see how far apart two areas are before asking for a bridge:

```
researchzosho bridges distance physics--quantum-electrodynamics astrophysics--black-holes
```

It prints the hops between them on the map, any shared terms and paths, how many sources name both, and
the nearest concepts on the two sides with their cosine from the embedder. Concepts closer than the fold
are walked as one node. The fold is 0.92, set by `RESEARCHZOSHO_BRIDGES_FOLD`. For scale, on a small
library "radiant heat" and "thermal radiation" sit at 0.85, and "electromagnetic field" and "magnetic
field" at 0.87. But "temperature" and "humidity" sit at 0.89. That is why the fold is not lower. The
embedder has to be running, or only exact names meet.

The settings, per area or for all. `researchzosho bridges settings <area> --reach high --loose --per-night 2`
sets them:

- **reach**: `low` pairs an area with its siblings, the areas in the same facet, such as `speech--…`.
  `medium` pairs it with areas a few hops away on the map, plus a tenth at random. `high` pairs it with
  anything, plus a third at random.
- **strict**, the default, needs three shared specific terms and no source that names both areas.
  `--loose` needs one term, and only that no claim is filed under both.
- **toward** an area measures distance toward it instead of outward. **away** rules areas out by word.
  **since** keeps only areas with a finding dated on or after a day.
- **sources**: `library`, the default, `peers`, or `web`. With `web`, two things happen. First, a search
  checks whether the two areas are already discussed together. A pair that is does not become a proposal.
  Second, a pair your shelves do *not* join is searched outside the library. The model names a few things
  that could bear on both areas. Each one is kept only if a search finds a source about it and about that
  area, on **both** sides. The source must be a journal, a reference work or the thing itself. A blog or a
  forum thread does not count. The proposal then shows the middle and both sources, so you can judge it
  in a few seconds. This covers the case where what connects two areas is named in neither of them.

The housekeeping runs one pass a night from the busiest areas and files up to three proposals.
`per_night=0` turns it off. Every proposal carries the settings that found it. `measure` reads that
ledger, so over time you can see which settings produce bridges worth keeping.

## 12d. Your databases

You can give the library read-only access to a database. Research runs and the chat can then answer
questions from your own data, and a report can cite the query it ran.

```
researchzosho db add shop postgres://reader@db.example:5432/shop --hide email
researchzosho db add notes ~/data/notes.sqlite
researchzosho db add tides ~/data/tide-csvs
researchzosho db list
researchzosho db schema shop
researchzosho db query shop "SELECT placed::date, count(*) FROM orders GROUP BY 1 ORDER BY 1"
researchzosho db remove shop
```

**Kinds.** SQLite, PostgreSQL and MySQL or MariaDB work out of the box. SQL Server, MongoDB and DuckDB
are downloaded when you ask, and each download is checked against a fixed checksum:

```
researchzosho db drivers
researchzosho db driver install mongo
```

| kind | address |
|---|---|
| SQLite | the path of the file |
| PostgreSQL | `postgres://user:password@host:5432/dbname` |
| MySQL or MariaDB | `mysql://user:password@host:3306/dbname` |
| SQL Server | `sqlserver://user:password@host:1433/dbname` |
| MongoDB | `mongodb://user:password@host:27017/dbname` |
| DuckDB | `duckdb:/path/file.duckdb`, or a folder of CSV, Parquet or JSON files |

Leave the password out of the address and you are asked for it, so it stays out of your shell history.

**Read-only.** Give the library a database user that can only read. On top of that, the connection is
opened read-only, every statement is checked before it runs, and nothing is ever committed. A statement
must be a single one and must start with SELECT, WITH, SHOW, EXPLAIN or DESCRIBE. For MongoDB, only
`find` and pipeline stages that read are allowed:

```
researchzosho db query mg --collection orders --filter '{"customer":"Ada"}'
researchzosho db query mg --collection orders --pipeline '[{"$group":{"_id":"$customer","spent":{"$sum":"$total"}}}]'
```

A folder of data files is loaded into DuckDB as one table per file. After loading, DuckDB's access to
other files is turned off.

**Where the address is kept.** In `~/.researchzosho/databases.json`, readable only by you. It is not in
the library folder. The password is never shown and never sent to the model.

**Hiding columns.** `--hide email,customers.ssn` hides the values of those columns in every schema and
every result. The column names still show, so a query can group or count by them.

**Rows and your model.** Rows that a query returns are shown to the model and saved in the library. If
your model is hosted elsewhere, `db add` and `db list` warn you that rows will be sent there.

**Limits.** A query returns at most 500 rows, 100 by default, and stops after 30 seconds. Count and
group in the query instead of reading every row.

**Results are sources.** Each result is saved as a page with the query, the time and the rows. A
report cites it like any other source, and the citation check reads the claim against the rows.

**Starting research from a database.**

```
researchzosho survey db:shop
researchzosho survey db:shop --pick 1,2
```

It reads the schema, saves one draft claim on what the database records, and lists research questions
the data could answer. Only the library owner's questions can use a database.

## 12e. Record sources

A web search cannot see inside a newspaper archive or a scanned directory. What a person did in life is
mostly in places like that. So a research run has one more tool, `record_search`, which searches one
collection of records at a time.

```
researchzosho records                                  the sources, and which need a key
researchzosho records test ndl-fulltext 高峰譲吉          search one now
researchzosho records key europeana <key>              save a key for a source that needs one
```

Each hit has a date, a link, and a line that says how to cite the record. The words shown beside a hit
were read from a scan by a machine, so the run opens the link and reads the record before it notes a fact.
A search that finds nothing is noted too, with the source and the words searched.

The run lists the sources in the language of your question first. Search a name the way the records
wrote it: in Japanese for a Japanese source, family name first, and again in the old character forms.

To add a source, for another country say, create `~/.researchzosho/record-sources.json`. It is a list of
entries like this one:

```json
[{"id": "my-papers", "name": "My country's newspapers", "holds": "newspaper pages, 1850-1950",
  "kind": "newspaper", "countries": ["XX"], "languages": ["xx"], "from": 1850, "to": 1950,
  "url": "https://papers.example/api?q={query}[&from={from}&to={to}]&rows={limit}",
  "items": "/results",
  "title": "{/paper}[, page {/page}]", "date": "{/date}", "link": "{/url}", "snippet": "{/text}",
  "where": "{/paper}[, {/date}][, page {/page}], My country's newspapers"}]
```

- `url` is the search address. `{query}`, `{limit}`, `{from}`, `{to}` and `{key}` are filled in.
  `{query:bare}` is the query without its quotes. A part in square brackets is left out when a value
  inside it is missing.
- `items` is where the list of hits is in the answer. The other fields are read from each hit:
  `{/a/b/0}` is a path into a JSON hit. For an XML answer add `"format": "xml"`, give `items` the
  element name of one hit, and write `{dc:title}` for an element inside it.
- `where` is how the record is cited.
- `kind` is `newspaper`, `book`, `archive`, `index` or `catalogue`. A page on an `index` or a
  `catalogue` source points to a record and is not counted as a primary source.
- A source that needs a key has `"key": "MY_KEY_NAME"` and uses `{key}` in its `url`.
- A source that signs in with a key and a secret (OAuth's "client credentials", as the European Patent
  Office does) has `"key"`, `"secret": "MY_SECRET_NAME"` and `"token_url"`, the address where the two are
  exchanged for a short-lived token. The search then carries the token. Set both with
  `researchzosho records key <source> <key> <secret>`.
- `"accept": "application/json"` asks a source for that format. `"empty_status": 404` is for a source that
  answers "nothing found" with that status: it is then a search that found nothing, not a failure.
- An answer made from XML is read as it comes: a list of one may be written as the one thing, a title may
  come once per language (the English one is taken), and the text of a part sits under `$`.
- An entry with the id of a built-in source replaces it.
- A site the library cannot search, because it has no search a program may use, is added as a link:
  `{"id": "parish", "kind": "link", "name": "The parish registers", "access": "free",
  "url": "https://parish.example/find?surname={family}[&born={born}]"}`. A run never searches a link.
  For each person, `genealogy research --list` and `genealogy log` print it with `{name}`, `{given}`,
  `{family}`, `{born}`, `{died}` and `{place}` filled in from your library. A part in square brackets is
  left out when a value inside it is missing, and a link whose other slots cannot be filled is not shown.
  `access` is `free`, `registration` or `subscription`.

The tool joins a run in genealogy mode, or on a software question, with that field's collections. In an entry, `"fields": ["genealogy"]` or `["software"]` says which. `RESEARCHZOSHO_RECORDS=always` gives it to every run, and `off` to none.

## 13. The map

A claim can say that a person lived in a place, an author wrote a work, or a company holds a
patent. The library records each of these connections. The map shows them.

```
researchzosho map "Arthur Ellis"
researchzosho map "Arthur Ellis" --depth 2
```

On the web pages, the map is at `http://127.0.0.1:4649/map`. Programs use `library_map`.

Subjects are on the map as squares. Each name is connected to the subjects its claims are filed
under. Type a subject's name or slug to start from it. If a name finds nothing, the map lists the
names it knows, spelled as they appear in the claims.

A write-up's claims are added to the map as soon as the write-up arrives.

Commands for the map:

```
researchzosho graph merge "A. Ellis" "Arthur Ellis" --because "<why>"   # two names are one person; the housekeeping only proposes this
researchzosho graph unmerge "A. Ellis"                # takes that merge back
researchzosho graph alias <node> <name…>              # other names for someone, in any language
researchzosho graph kind <node> person               # file a node under a kind: person, place, event, work, or any other word
researchzosho graph link <node> Q123                  # link to a Wikidata entry
researchzosho graph proposals
```

`graph merge` joins the first name into the second and keeps your reason in `catalog/graph/merges.tsv`.
It lists the claims that are now about the second name. `graph unmerge <a> [<b>] --because "<why>"`
takes that one merge back: the merge stays in the file, and a line after it takes it back. The name the
merge gave the other node as another name is removed. Neither command rewrites a claim.
`catalog/graph/different.tsv` keeps the pairs somebody said are two different people (`genealogy
different`), and the proposals leave them out.

For places, a proposal needs the place and what it lies in to agree, compared part by part: the parts
between commas, or for a Japanese address the prefecture, 郡, 市, 区, 町 and 村. Springfield, Illinois,
USA is proposed as Springfield, Sangamon, Illinois, USA, but not as Springfield, Ohio, USA, and York,
England is not proposed as New York, England. An address written in old character forms is proposed as
the same address in new forms.

## 14. Fields

The core library has no built-in subject knowledge. A field adds some.

Every field is on. `researchzosho profile disable <name>` turns one off for this library.

### Goals, sources and conventions

The fields are sorted three ways. A goal is what you want done: find a home, find work, a dossier on a person or an
organisation, buy a thing, prior art, what the law says, the family, learn a craft, choose something to read, watch or
listen to, or simply answer the question. A source is a place to look: the web, the scholarly indexes, the public
records, the archives, old newspapers, forums, videos, podcasts, shops, book catalogues, software archives, your own
shelves. A convention is how a claim must be stated in a subject, whatever the goal: a claim of law carries its
jurisdiction and the date it stood, a scientific claim cites the record, a patent claim carries its number and its
claims. A question can carry several at once. A research run that asks for homes or for jobs now follows the home or
the work goal's rules, listing the homes or the openings that fit with a link on each line, even when you did not
start it from the Home or the Career page. A run that asks what to buy, how to make or do something, or which book or
podcast to pick follows the buy, learn-a-craft or choose goal's rules the same way: its candidates are enumerated and
judged by fit, never taken from a chart, a best-seller list or an engine's first page, and the report says what was
checked and what was not. Each field below is one of the three kinds and works as it did.

- `science` looks up citations from the record, tracks preprint versions, and
  writes BibTeX with `researchzosho bib <id>`.
- `podcasts` is for questions about a podcast, an episode, a host or a co-host. It joins a run by the
  question's words: the workers read what the person said in the episodes, with the time, before
  articles that quote them, and every spoken claim is cited by the episode and the time. Section 10c.
- `software` is for questions about projects, libraries and tools. When a question names GitHub, open
  source, a repository or a package index, the research run searches GitHub and the package indexes
  directly, by best match and by most recent push. It judges a project by its last push, its licence and
  its own README, not by its stars, and gives the answer as a table.
- `crafts` is for doing and making: any craft or hobby, a repair, a build. It joins a run by the words of
  doing ("how do I make", "what do I need to", "pattern for", "作り方") and answers with the how and the kit:
  every material and tool, what it is for, what substitutes for it, and where to get it. For an old way of
  doing something it gives how it was done, how to do it now, and the modern stand-in for each historical
  material with the reason. It carries a list of the kinds of doing that the open taxonomies name, about
  thirteen hundred of them, with Japan's designated crafts by category and prefecture and the Heritage
  Crafts Red List with each craft's risk band; the run is told what the list knows of the kind you asked
  about. The list is made by `scripts/crafts-kinds.py`, never by hand.
- `genealogy` adds family relations, adoption and household heads included. It acts only on the
  family history you ask for: its own commands and pages, and a research run asked for in genealogy
  mode (`research ask … --genealogy`, the box on the Research page, `field: "genealogy"`, or a yes to the
  chat's question). Such a run follows genealogy's rules: it works from records, ties a record to a
  person by more than a name, notes searches that found nothing, and lists the records only the family
  can request. Asked with `--goal family` it is the same run: each person is identified by the family's own
  account first, each name is searched under the form the person carried in those years, and every question is
  answered as a record, as not found with where it was looked for, or as records that disagree. Everything else in the library works as if genealogy were off: a question that only
  looks like family history is told that genealogy mode is there, and runs as ordinary research; the
  map reads family relations only in the family's own claims and in the claims of genealogy-mode runs;
  the Family tree, Who is who and Decisions pages are under "all pages" once the library holds family work.
  `researchzosho profile runs list` shows which runs were genealogy runs, and `profile runs forget` or
  `profile runs add` corrects that list.
  The whole way through, step by step, is in [FAMILY_HISTORY.md](FAMILY_HISTORY.md).
  Start from what your family already knows. You do not need a family-tree file:

  ```
  researchzosho genealogy read aunt-notes.docx --by "my great-aunt Hanae"
  researchzosho genealogy read https://ja.wikipedia.org/wiki/…
  researchzosho genealogy tell "My grandfather 髙橋正一 was born in 明治41年 in 広島県佐伯郡." --by "Mara"
  ```

  Each person becomes a node and each relation a draft claim, with the sentence that says it and the
  account as its source. Nothing is checked yet. A research run then looks for the records. Dates stay
  as written with the year beside them, 明治41年 (1908). Old and new character forms of a name, 髙橋 and
  高橋, are the same person. A person counts as possibly living until their dates show they died or place
  them more than 110 years ago; the research then asks about their work and public life, not their death.

  `researchzosho genealogy read ~/family-sources` reads a whole folder: notes first, then a tree file, lists
  of links and pictures, the books last and kept to the family's names. Each file is read once.

  `researchzosho genealogy tree "髙橋正一"` draws the family around a person into `family-tree.svg`, which
  any browser opens. The library's pages draw the same at `/tree`. A grey line is a claim nobody has
  checked yet, a green one is accepted, a dashed one is an adoption.

  `researchzosho genealogy research` writes a research question for each person in the tree and lists the
  people, the least known first, each with what is not known yet about them. It asks which to start with:
  numbers such as `1,3`, `all`, or Enter for nobody; then whether to put the others on the nightly waiting
  list (Enter is no). Nothing starts or waits by itself, and only the people you pick or put on the list
  are looked up on the web. `genealogy research "髙橋まり"` researches the
  people you name and not their relatives, and says for each what happened; `--family --up 6 --down 3`
  lists their relatives too. `--skip-living` keeps to those who have died, `--list` only shows the
  questions. Give it again when the reports have landed and the tree has grown.

  `researchzosho genealogy life "髙橋源三郎"` shows a person's life in order of date: every claim about them,
  with its state and where it is from.

  `researchzosho genealogy check` lists what cannot be true: a child born after a parent died, three
  birth parents, a person who is their own ancestor. These usually mean two people of one name were
  taken for one. It also lists names that may be the same person. It changes nothing.

  When two people share a name, move the second one's claims to a name that tells them apart:
  `researchzosho genealogy split "John Ellis" --as "John Ellis (born 1851)" --claims F-0003,F-0007`.

  `genealogy import tree.ged` reads a family-tree file. Each person becomes a node. Each relation
  becomes a draft claim with the file as its source.
  `genealogy import tree.ged --dry` says what an import would do, and who in the file may already be in
  your library, and changes nothing. `genealogy export "Arthur Ellis"` writes a family-tree file out.

  `researchzosho genealogy source <file name or address>` lists every fact that rests on one source, and
  which of them have no other source.

- `books` is for questions about which book to read, what a book covers or what to read next. The answer comes
  from the library catalogues; a book no catalogue returned in the run is not named. With your Calibre library
  set, it knows your shelf and leaves out what you have. Section 10g.
- `shopping` is for questions about what to buy. It looks only at what shops list right now in your market,
  checks each candidate for its datasheet, recalls, repairability and what owners say, and judges fit against
  your need, never popularity. Section 10f.
- `youtube` is for questions about what is on YouTube: which channels make a kind of video, whether a channel
  still posts, what is said in a video and at which moment, and which channels are like one you know. It needs
  no account and no key. [YOUTUBE.md](YOUTUBE.md) has it in full.
- `career` is for looking for work: it reads your working life from a resume or a LinkedIn file, asks what you
  want next, and looks for openings at the companies that fit. It has its own page and commands and never joins
  a research run. Section 26b.

## 15. Running it as a service

```
researchzosho service install            # Linux, macOS or Windows; a user service, no administrator rights
researchzosho service status
researchzosho service restart            # a job that is going stops, and resumes where it was when the service is back
researchzosho service uninstall
```

The service listens on `127.0.0.1:4649`. It runs the housekeeping at three each morning;
`--crew-hour` changes the hour. It writes its log to `~/.researchzosho/logs/librarian-serve.log`.
On Linux, `loginctl enable-linger <you>` keeps it running while you are logged out.

Run by hand, `researchzosho serve` writes its log to the terminal; where no terminal is attached, in a container or
under a cloud runner, it writes to stdout, and `RESEARCHZOSHO_LOG=stdout` or `stderr` says so outright. At start it
names each setting that is missing, in one sentence each: no model answering, no account for the person who keeps
the library, pages open on a network address, no embeddings server, no web search. `GET /healthz` answers with the
version and the library's id, with no sign-in, for a proxy or a platform that checks before sending people here.

**Behind a proxy.** In the cloud the service sits behind a reverse proxy or a load balancer that ends the
encryption and adds the forwarded headers. `RESEARCHZOSHO_TRUSTED_PROXY` names it, as addresses or networks
(`10.0.0.0/8`, `127.0.0.1`): only a request from one of them has its `X-Forwarded-For` and
`X-Forwarded-Proto` believed, and the relay identity headers (`Tailscale-User-Login`,
`Cf-Access-Authenticated-User-Email`) are read from it as they are on the shared door. The sign-in cookie
is marked Secure when the proxy says the person came over https, and a proxy on the same machine never
passes for the person who keeps the library. `RESEARCHZOSHO_SESSION_DAYS` is how long a browser stays
signed in, a year unless set. [CLOUD.md](CLOUD.md) is the page for a server that holds many libraries: one
router, one container and one volume per library, sign-in through your identity provider, keys for programs,
backups and moving, and one VM on AWS.

By default only the computer it runs on can reach it. To reach it from other machines on your network:

```
researchzosho service install --host 0.0.0.0
```

It then also answers on the computer's network address, for example `http://192.168.1.20:4649/`.
Read section 16 on sign-in before you do this. Do not open that address to the internet itself:
there is no encryption on it, and tokens would travel in the clear. To reach the library from
outside your home, use `share`, below.

### From outside your home

```
researchzosho share                      # reachable now: a Cloudflare quick tunnel (no account), or Tailscale Funnel once Tailscale is set up
researchzosho share --tailscale          # the address that lasts: installs Tailscale, logs in, Funnel (the better long-term setup)
researchzosho share --tailnet            # the same, on your tailnet only
researchzosho share --cloudflare         # the quick tunnel even when Tailscale is set up (free; no account)
researchzosho share --cloudflare --mail ann@example.com,*@example.org   # only these get through, with a one-time code
researchzosho share status               # the address, the door, the tunnel
researchzosho share off
```

`share` puts the library behind an encrypted relay that the library dials out to, so no port is
opened at your home and it works behind a carrier's NAT. With neither program on the computer it
installs what it needs, asking before each step: Tailscale the way Tailscale installs it (its own
script on Linux, Homebrew on a Mac, and Homebrew first when the Mac has none; winget on Windows),
then `tailscale up`, which shows a link to log in with. The installer asks for your password on the
screen; the library never does. Or cloudflared, one program fetched into the library's own folder,
with no account and no password. Nothing is installed without your yes (`--yes` answers for a
script). The same from the chat: say `/share`. The service opens a second door on this computer
(`127.0.0.1:4651`) that only the relay points at; `4649` stays as it is.

**The plain `share`** makes the library reachable now. With Tailscale set up and logged in on the
computer it uses Tailscale Funnel, the address that lasts. Otherwise it fetches `cloudflared` into
the library's own folder, no account and nothing asked, and opens a quick tunnel: you are reachable
within half a minute at an `https://….trycloudflare.com` address, which `share` prints. That
address changes whenever the service restarts the tunnel, so send an invite when the person can
open it straight away; for an address that lasts, run `share --tailscale`.

**With Tailscale** (`share --tailscale`, the better long-term setup): the library installs it,
asking before each step, runs `tailscale up` with its login link, and uses Funnel. The library is
reached at an `https://…ts.net` address from anywhere, and that address never changes: anyone with
the link sees the sign-in page and nothing else; a reader gets in with their name and password (or
a passkey), and nobody without an account gets in. Funnel and HTTPS certificates are each approved once in the admin
console, and on Linux `share` sets `sudo tailscale set --operator=$USER` once; when Tailscale
refuses, `share` says the exact next step. Once Tailscale is set up, the plain `share` uses it.
`share --tailnet` keeps the same address to your tailnet only; there Tailscale tells the library
who is arriving, so a reader you listed under their login (`did:mail:<login>`) is signed in as they
arrive, and anyone else can ask to be let in with their name filled in. A personal tailnet is free
for six people. A paid or business tailnet uses the same command.

**With Cloudflare** (the fallback): install `cloudflared`, then `share --cloudflare`. The service runs
a quick tunnel and keeps it running; its `https://….trycloudflare.com` address changes at every start,
so `share status` and the Settings page show the one in force, and join links use it. With `--mail`,
only the listed addresses get through, after a one-time code from Cloudflare; without it, anyone
with the address reaches the sign-in page. A quick tunnel is meant for light use and has no uptime
promise. A named Cloudflare Tunnel you already have works too: point its ingress at `127.0.0.1:4651`.

In either case the first `share` turns sign-in on. Through the door, only a reader you listed gets
in: invite people by name (`reader invite`, section 16), or let them in when they ask. Nothing you
do on this computer changes: the pages at `127.0.0.1:4649` work as before.

**From another computer.** With ResearchZosho installed there too, and no library of its own needed:

```
researchzosho connect https://mylibrary.tail1234.ts.net <token>   # the address share printed, a token from reader token
researchzosho ask how were the gears cut                            # and search, get, read, established, research ask,
researchzosho research ask "…"                                       #   jobs, status, changes, subjects, questions, map,
researchzosho connect                                               #   perspectives, inbox: they all go to that library
researchzosho connect off                                           # back to this computer's own library
```

`researchzosho mcp` while connected relays Claude Code, Codex or Gemini on that computer to the
library, with the token, so they get its tools without a service of their own. The verbs that touch a
library's folder or its computer (setup, service, model, add, reader, share, settings…) say to run
them on the library's computer.

## 16. The web pages

Open `http://127.0.0.1:4649/` in a browser. The header carries the rail, Chat, Past and Updates, and Keep for the
person who keeps the library; "all pages" beside it opens every page, grouped: the library (Home, Add, Ask, Search,
Subjects, Map, Dossiers, Pictures), the work (Inbox, Open questions, Runs, Research, Sources, Suggested, Changes), what is yours
(Chat, Help, Owned, Settings, Account) and each field's own pages. The pages are light, and dark when the computer is
set to dark; the switch in the header, system, light or dark, is remembered by the browser, and so is the language
beside it, 日本語 or English (section 9). The pages are:

| Page | What it shows |
|---|---|
| Chat, the home | The conversation with the Librarian. Each answer carries a mark saying where it came from: the shelves, a research run it started, the library itself, or the model's own words; under it, what was looked up on the way and the claims it cites, each with its standing (● established, ○ not yet) and its sources. The answer appears as the model writes it, with a caret and a stop beside it; the stop ends the turn within seconds and keeps the words that came. A model that cannot stream shows its answer whole, after a line that says it is being written. The runs the conversation follows stand beside it, a bar each, with a stop; the earlier conversations open from a button. Enter sends, Shift+Enter breaks a line. Files chosen with "@ Attach", dropped onto the page, or pasted as a picture, one or several or an archive, go onto the shelves with the turn, by the Add page's own path, and the turn says what came of each, so the Librarian can read them; before sending, each shows as a chip with its name and size and a × that takes it off, and one message carries up to ten. A question the library asks with named choices, which of these is meant, shows as a button for each; the number or the name typed answers it too, and as with yes or no, no answer within ten minutes counts as none. |
| Books | Your shelf as the library reads it from Calibre, the subjects you go for, the books you loved, liked and chose, and the books reports; for the owner, a reaction to a book, the preferences file shown and changed, and the DNA's records read now. |
| Podcasts | The shows followed, each with its feed and cadence, and the podcasts reports; a form follows a feed or stops following one, and the owner reads the transcripts held for their DNA. |
| Video | The videos the library has read with their three tracks, what was said, the words shown and what is seen, the pictures looked for, and the videos reports; Past has a row for the videos read. |
| Connect | The first screen, in the chat until a model has answered once, and a page of its own: a model you own on this machine, found on the usual ports or given by its address; one you rent, by host, key and model; a test that says what answered; and a way to add things while you choose. |
| Past | Every report as a row with one line of state, newest first: research reports being made, filed, declined or failed, dossiers, and the fields' own reports, a person's job search, a home search, a picture looked for in the videos, your shelf of books and the podcasts you follow, and a field's own reports under the field's name (books, podcasts, videos…). Chips narrow the rows by kind and by state; a box searches the titles. Above them each field's own section and the library's counts; below, the open questions. |
| Ask | The same answer as `researchzosho ask`. |
| Search | Search of the library. |
| Entry | A claim, write-up, summary or saved document in full, with its sources, notes, review and connections. A report's page opens with one line of state (when it was filed, what the cite-check found, its claims and open questions, what waits for your review), the claims that came out of it each with one mark, ● established or ○ not yet, and "How this was made" behind a fold: who asked, the model, the time, the run's record and the cite-check. A diagram the report wrote as Mermaid text is drawn on the page, with its text one click away; a picture it placed is shown at reading size and opens whole on a click. Opened from Past, a report stands beside the chat. A report's page has "Go deeper": the questions it left open, each sent as an in-depth run with one button, and a box for your own follow-up; the run is given the report first. |
| Subjects | The subject list, and the subjects the cataloger proposes, accepted or dropped by tick boxes. |
| Updates | What changed on your reports since you last looked, newest first, with the count in the rail: a run that landed with the claims it left for review, a run that started, a question the library waits on, a claim that came back, was accepted, disputed or retired, who asks to be let in. "Mark all as seen" makes now the last look. |
| Inbox | Claims waiting for a decision, grouped by the report they came from and filtered nine ways. Tick any number, then accept, retire, or dispute them with a reason. |
| Open | The searches kept up to date, and the open questions, grouped by the report that left them and filtered eight ways: tick any number, then send them as runs, reorder, park, or drop them. |
| Changes | What changed, newest first. |
| Runs | What the housekeeping will do tonight; research runs, running and finished. "Pause the runner" holds every run at its next turn until "Resume"; "Stop" beside a run ends that one. |
| Research | Send a question, or sharpen it first. |
| Seeks | The same question researched several times apart, each seek with its run and report; a button merges the ones that landed into one report, another drops the group. |
| Perspectives | Who studies a question and what each would ask; a link sends any sub-question as a run. |
| Submit a claim | A claim of your own with the sources that back it, filed as a draft that waits in the inbox. |
| Forecasts | The dated forecasts the reports made, with their likelihood and outcome; a form scores one once its date has passed, and the Brier score follows. |
| Bridges | The open proposals, each with a button that files the run testing it or drops it; a form looks for new pairs from an area, shown first and filed on a second click. |
| Read in | Your bookmarks, pasted or by the export's address, onto the shelves as a collection; a repository, paper, website or issue tracker surveyed by its address, its directions ticked and run. |
| Databases | Your databases, their schema, and one SELECT at a time with its rows saved under a locator; the owner's alone. |
| Keep | The keeper's things behind one link, each behind a fold with its gist: the connection and the models, the sources and their yield, people and access (invite, what anyone not listed may do, an id listed by hand, a token for a reader's program), the other MCP servers (each tool let on or kept off, the monthly cap), the tally, the settings, and the rest. A reader sees that the keeper looks after these. |
| Settings | Every setting the library has, with what it is for and its value; change one from the form. A key is shown only as set or not. |
| Owned | What you own, from your own purchase records: a search over it, and where each imported file's records begin. |
| Map | The map. |
| Pictures | The pictures the library keeps, as tiles with their pages, verdicts and where they came from, and a search for pictures by words, the shelves first (section 8c). |

A page is never cached by the browser, so what you see is the library as it is now. If a browser was signed in with a
token the library refuses now, the page that refuses it also clears that sign-in; load the page again and you are an
ordinary visitor, or sign in with another token.
| Who is who | Family history: for each person in your family, the people the web shows under their name, with links. You tick the one who is your relative, or say none of them is, or leave it for later, and you can write something about the person. One person at a time; leave whenever you like, and the Inbox says how many still wait. At `/who`, for people who may write. |
| Decisions | Family history: what only the family can settle, in one place. Names that may be one person, then two sources that disagree about one person, then who is who. Each answer says what it will do before you give it. At `/decide`, for people who may write. |

Every entry has "Read it: beginner · familiar · as written" and a Download line. A dossier's page takes what you
tell of its subject, in your own words, filed as told; writes the story on a button; and scores a forecast in its row.

### Sign-in

As shipped, anyone can use the pages, and Keep says so. To require a sign-in before a
question can be sent:

```
researchzosho web signin on                      # asks for your own name and password first, if you have no account yet
researchzosho account set                        # make or change your account any time (asks; or --name N --password P)
researchzosho reader invite "Ann Example" read   # one link: she opens it within seven days and chooses her password
researchzosho reader link "Ann Example"          # a new link, for a forgotten password
researchzosho reader password "Ann Example"      # a password you type for her
researchzosho reader token <did>                 # a token, for a program the keeper names
researchzosho keys new "the notes script" --write # a key of your own, for a program: shown once, kept hashed
researchzosho keys list · keys revoke <id>        # the Keep page and your account page do the same
researchzosho web signin off
```

**Sign in through your identity provider.** A company or a school signs its people in through Google
Workspace, Entra, Okta or any provider that speaks OpenID Connect. Register the library there as a
web client with the callback address `https://<your address>/login/oidc/callback`, then set
`RESEARCHZOSHO_OIDC_ISSUER` (the provider's issuer address), `RESEARCHZOSHO_OIDC_CLIENT_ID` and, when
the provider gave one, `RESEARCHZOSHO_OIDC_CLIENT_SECRET`. The sign-in page then offers "Sign in
with <provider>"; `RESEARCHZOSHO_OIDC_NAME` names it. Someone already listed here by their mail
address is signed in as that reader. Someone new is let in as a reader when their verified mail
address is at a domain in `RESEARCHZOSHO_OIDC_ALLOWED_DOMAIN`, and the person who keeps the library
raises them to write; anyone else is told to ask. The library checks every identity token against
the provider's published keys, its issuer, its audience, its time and the nonce it sent.

A person signs in with their name and password, from any phone or computer. On the library's own
computer your browser is you, with no sign-in, when it opens the library by this computer's own name: localhost,
127.0.0.1 or the computer's name (`web.local = signin` in the settings turns that off, for a computer other
people use). A library that listens on this computer only answers to localhost and 127.0.0.1, so a page on
another site cannot reach it by pointing a name of its own at your computer. `share` asks for your account before it opens the library, so you
can get in from outside yourself. Every form on the pages is carried out only when your browser says it
was sent from the library's own page, so a page on another site that you happen to open cannot send one
in your name; a program's call with a key is no form and is not asked.

`reader invite` prints a link. Send it to the person; when they open it, they choose a password, and
from then on they sign in with their name and it. They stay signed in for a year in that browser. The
Settings page has the same under Readers: who may read and write, how each signs in, an Invite form,
and buttons to change a reader's level, remove them, give them a new link, or set a password you typed.

A person with no link can ask from the sign-in page: "Ask to be let in", with their name, a password of
their choosing and a note. The request appears at the top of your Inbox, with "Let in to read", "Let in
to write" and "Refuse". When you let them in, their next visit signs them in, with the password they
chose; nothing is sent anywhere. The same request can come from a program through
`library_request_access`, and `reader requests`, `reader approve` and `reader deny` answer it from the
terminal.

**The Account page** (Account, in the menu) is where a person changes their name or password, adds a
passkey, sets up a one-time code, and signs every browser out. A passkey signs you in with your
fingerprint, your face, your phone or a security key, with nothing to type; it is made for the address
you are at and works there. The browser only offers it at an address with a name, not a number:
the https address `share` gives, or `http://localhost:4649/` on the library's own computer (not
`127.0.0.1`, and not a home-network number like `192.168.1.20`; the page says so). The one-time code is a second step after the password: the
page shows a QR code and the same secret as letters; scan it with an authenticator app (Google
Authenticator, Microsoft Authenticator, Aegis, 1Password and the like), type the code the app shows,
and from then on the library asks for the code of the moment after your password. A passkey needs no
code. `researchzosho account reset` takes away your own password, passkeys and code and signs every
browser out, for a fresh start; `reader link` does the same for a reader.

Passwords are kept as salted, stretched hashes, never as themselves. Ten wrong passwords at a name
make the next tries wait ten minutes.

Programs are open too, as shipped. Anyone who can reach the service can read, ask, and file runs.
To restrict that, set the default level for callers not on the list:

- `researchzosho reader default read`: they can read and ask, but not file runs or submit claims.
- `researchzosho reader default deny`: nothing without a name on the list.

Then `researchzosho reader allow <did> write <name>` lets a named program through. The list is
`catalog/patrons.md`. `reader list` shows it.

Someone who may read sees everything in the library, on the pages, in the chat and through a program:
every claim, report, saved page, question, research run and explanation, the family's own texts, and the
living in a family as much as anybody else. To keep the library from somebody, do not give them read
access. What changes the library, such as a decision in the inbox, an answer on the Who is who or
Decisions page, or a research run, needs write access.

A conversation in the chat belongs to whoever had it. Someone who may only read has conversations of their
own, which nobody else opens, and never opens yours.

### Waiting and double sends

Some requests take time, for example sharpening a question or writing a simpler version. The page
returns at once, shows what it is doing, and updates itself until the result is ready. A second
click on Send, a refresh, or the back button does not send a question twice. The page shows what was
already sent.

Three instruments have a form of their own on the pages, with no search or run started first: on the Video page,
"Look at a video" takes a video's address and what to look for in it; on the Home page, "Records of an address" reads
the public records of any US address; on the Career page, "An employer's board" reads an employer's or institution's
own careers board and its openings. Each answers on a page of its own. In a research run the board takes the title
words wanted and lists only the openings whose title holds one of them, counting the rest, so a board of hundreds is
cut by the program rather than read by its first page.

## 17. Obsidian and SoloMD

The library's own files are records. Do not edit them by hand. The library can write a separate
folder for an editor:

```
researchzosho vault                      # writes <library>-vault next to the library
```

Open that folder in Obsidian, SoloMD, SilverBullet or any editor that reads Markdown with links. Start
at the Home note. Every claim, write-up, saved document, subject, person and place is a note with
frontmatter and links. The editor's graph view shows the same connections as the map. The Inbox note
lists what is waiting for you.

Once the folder exists, the service keeps it up to date. It rewrites the folder within seconds of
any change to the library. Only notes that changed are rewritten.

The vault is a one-way view. Editing a note there does not change the library. Notes you add are left
alone. A note the library wrote is not overwritten after you edit it. To accept or dispute, use
`researchzosho inbox` or a program connected over MCP.

Obsidian needs nothing installed for this. Two things help:

- Bases, built into Obsidian, turns the notes' frontmatter into tables. For example, every claim by
  state, or everything under one subject.
- The AI Copilot community plugin can connect to `researchzosho mcp` as a local server. You can then
  ask the library from the sidebar, using your own key or a local model.

SoloMD has no plugins and needs none. Its agent panel works with the vault. To talk to the library
itself, use the web pages or `codezaiku chat`.

## 18. Other programs

Anyone or any program that uses the library is a reader. You decide who may read and who may write.

The web pages and the vault need no program. To talk to the library, `codezaiku chat` runs on a local
model, for free, and connects to the service. What the library holds is pushed into each turn.
`/librarian` asks it. `/research` files runs with it. `codezaiku install researchzosho` sets that
up, including the write token the chat needs to file runs.

Claude Code, Codex and Gemini CLI can use the library directly, without the service running:

```
claude mcp add --scope user librarian -- researchzosho mcp
codex mcp add librarian -- researchzosho mcp
gemini mcp add -s user librarian researchzosho mcp
```

A program registered this way can do everything, as shipped. If you have restricted the library
with `researchzosho reader default read` or `deny`, allow the program by name. The refusal it gets
names its id. `researchzosho reader allow <did> write "Claude Code"` puts it on the list.

Setup gives each program one identity per machine and person. The id looks like
`did:key:local-claude-…` and stays the same however often setup runs. So `reader list` shows one
line per program, not one per run of setup. A library set up before 0.1.5 carries `default: read`
in `catalog/patrons.md` from the setup of the day. `researchzosho reader default write` opens it,
the same as a fresh library.

A program that runs MCP servers through `npx` can start the library without installing it first.
`npx -y @wyrdsekai/researchzosho-mcp` finds an installed ResearchZosho and starts `researchzosho mcp`.
If none is installed, it fetches the release of the same version, checks it against the release's
checksums, and unpacks it under `~/.researchzosho/launcher`. It takes the small tarball when the
machine has Java 21, otherwise the build for the platform that carries its own runtime. A machine
with no library gets one made.

```
claude mcp add --scope user librarian -- npx -y @wyrdsekai/researchzosho-mcp
```

The library also runs as a container, `ghcr.io/wyrdsekai/researchzosho:<version>`. The library and
the settings are on volumes, and the pages are on 4649. The `docker-compose.yml` in the repository
runs it beside an embedder. `docker run -i --rm -v $PWD/library:/library ghcr.io/wyrdsekai/researchzosho:0.6.1 mcp`
runs the same MCP server over stdio, from the container. The model server stays outside. Name it in
`RESEARCHZOSHO_DRIVE`.

Any other program that speaks MCP uses the same server: the command `researchzosho` with the
argument `mcp`. In JSON form:

```
{"mcpServers": {"librarian": {"command": "researchzosho", "args": ["mcp"]}}}
```

Or through the service, with a token so that submissions carry a name:

```
researchzosho reader allow did:key:me write "my name"
researchzosho reader token did:key:me
claude mcp add --transport http --scope user librarian http://127.0.0.1:4649/rpc --header "Authorization: Bearer <token>"
gemini mcp add -s user -t http librarian http://127.0.0.1:4649/rpc -H "Authorization: Bearer <token>"
```

The tools are: ask, search, get, read, established, submit, research, sharpen, explain, job,
map, perspectives, frontier, subjects, status and changes. [LIBRARY_PROTOCOL.md](LIBRARY_PROTOCOL.md)
describes each one. Since 0.7.0 a program also has `library_look` (a video's frames and transcript read for what you
ask of it), `library_records` (the public records of an address), `library_board` (an employer's own careers board) and
`library_export` (a report or a dossier saved as a PDF or a Word file),
and `library_research` takes `goal`, the goal the run serves, which chooses the report's shape and the instruments it
brings.

### Other MCP servers as sources

The library can use other programs' MCP servers while it researches. A server's tools sit beside the
library's own searches, and a research run or the chat calls one when the question needs what that
server holds: your notes, a code host, a search engine.

Add a server that runs on this computer by the command that starts it, or one on the internet by its
address:

```
researchzosho mcp add notes -- npx -y @example/notes-mcp
researchzosho mcp add docs https://docs.example.org/mcp --header-key mcp_docs_key
```

The second asks for the key the server wants and keeps it in the setting `mcp_docs_key`. The library
never prints a key. Two search engines need no address: `researchzosho mcp add exa` and
`researchzosho mcp add tavily`. Both work without a key, slower and with a limit, and use yours once
you set `exa_key` or `tavily_key` with `researchzosho settings set`.

`researchzosho mcp list` shows each server, whether it answers, and which of its tools research may
call. `researchzosho mcp tools <name>` goes through a server's tools one by one. The Keep page has the
same list, a button to test each server and a form to add one. A server that runs on this computer is
started when research first needs it and stopped after five idle minutes; what it prints is kept in
`catalog/mcp/<name>.log` in the library.

Research reads; it does not act. A tool whose name or description says it creates, writes, sends,
deletes, buys or runs something is left out, and `researchzosho mcp tools <name>` says why for each.
`researchzosho mcp allow <name> <tool>` lets one in anyway, and `researchzosho mcp forbid <name> <tool>`
keeps one out. Another ResearchZosho library added this way (`-- researchzosho mcp`, with its folder in
`--env RESEARCHZOSHO_LIBRARY=…`) is known by name, and research calls only its tools that read.

What a server answers is read like a page from the web: as evidence, never as instructions. It is
cited as `mcp://<name>/<tool>` with the arguments it was called with, and the addresses in the answer
are sources of their own.

Every call is counted in the month's tally. `researchzosho mcp cap <name> <calls>` sets the most
calls a month. Exa starts at 714 a month and Tavily at 1,000, the same limits as their search places.
A tool that runs the search engine's own research costs more and takes minutes, so a small model is
not offered it.

Only you add and change servers. You and the people who may write use them in their research and
chats. Readers use a server only after `researchzosho mcp readers <name> on`.

## 19. Change notifications

A program that cites your claims can be told when they change, instead of checking every day.

```
researchzosho reader webhook add did:key:zW https://their.example/hook --events retired,revised
researchzosho reader webhook list
researchzosho reader webhook remove did:key:zW https://their.example/hook
```

Every change on the feed is posted to the address: a claim retired, disputed or revised, or a source
you supplied for a page that could not be read. Each post carries the change, the library it came
from, and a signature made with a secret the reader was given. If the address does not answer, the
library tries three times and then writes a line in `catalog/webhooks.log`. The changes feed stays
the record. A missed post is caught the next time the reader asks for changes.

A webhook is sent changes only while its reader may read the library. When you deny a reader, or
close the library with `researchzosho reader default deny`, their webhooks are sent nothing more, and
each change not sent is written in `catalog/webhooks.log`. `reader webhook remove` takes one away.

A program can subscribe itself with `library_subscribe`.

## 20. Other libraries

If the keeper of another library has given you a reader token, your library can ask theirs.

```
researchzosho peer add ana https://ana.example <token> --group family
researchzosho peer add lab-archive https://lab.example <token> --group lab
researchzosho peer list
researchzosho peer ask family "where did Tanaka Ichiro live after 1920"
```

The answer comes back one library at a time, labelled with whose it is. Nothing is copied or merged.
To keep one of their claims, accept it. It then carries their library's name as its source.

Two rules:

- One hop only. Your library asks its peers, never its peers' peers.
- A peer sees only what its reader level allows.

A program can name a peer, a group or `all` in the `peers` field of `library_ask`. With
`peers.default = family` in your config, an ask that finds nothing at home asks the family group.

An agent that speaks A2A (the Agent2Agent protocol) can call the library too, while it is shared.
Its card is at `/.well-known/agent-card.json` on the shared address, and its calls go to `/a2a`.
Two skills: `ask` answers from the shelves at once, as `library_ask`; `research`, chosen with
`metadata.skill = "research"` on the message, files a research run and answers with a task, which
`GetTask` follows until the investigation is written and `CancelTask` stops. The caller sends one of
your reader or writer tokens as its bearer token, and sees what that token may see; research needs
a writer's.

## 21. Access requests

Someone who finds your library can ask to be let in. `library_request_access` takes a did, a name
and a note. It works even on a library whose default is deny. They receive a request id and a claim
secret, shown once. `researchzosho status` shows "access requests waiting".

```
researchzosho reader requests                          # who asked, when, and what they wrote
researchzosho reader approve R-0003 read               # or write
researchzosho reader deny R-0004 "not a member of the society"
```

Approving adds them to the reader list and makes a token. They collect it with `library_access`,
using the request id and their claim secret. They can collect it exactly once. A denial returns your
reason. Limits: one pending request per person, fifty per library. A request expires after thirty
days.

## 22. Directories

A directory is a library whose claims are listings of other libraries.

```
researchzosho directory publish https://family-libraries.example --token <t> --url https://mine.example
researchzosho directory find https://family-libraries.example "who has records of Osaka families"
researchzosho directory list                        # where this library is listed
```

`directory publish` submits one claim to the directory: your library's name and id, its address, up
to twelve of its subjects, and where to ask for access. It says nothing about the library's contents.
The claim is a draft until the directory's owner accepts it. You need a writer's token on the
directory to publish.

`directory find` searches the directory. The result is a name and an address. Request access as in
section 21.

Anyone can run a directory. It is a library with its default access set to read and a few writers.
There is no central directory. Only you can list your library.

## 23. Seeks: separate pulls and one merge

A question worth more than one look can be researched several times apart. Each seek is an ordinary
research run on its own copy of the shelves, so no seek reads what another just found, and nothing a
seek writes reaches the library until the merge keeps it. The seeks of one question form a group:

```
researchzosho research ask "…" --seek gedcom-7                    # on the library's model
researchzosho research ask "…" --seek gedcom-7 --model deepseek   # the same question on another model
researchzosho research ask "…" --seek gedcom-7 --web              # blind to the shelves
researchzosho seeks                                               # the groups: each seek's state and report
researchzosho seeks read gedcom-7 J-0126                          # one seek's report
researchzosho seeks merge gedcom-7                                # one report from them, as a run
researchzosho seeks read gedcom-7                                 # the merged report
researchzosho seeks drop gedcom-7                                 # the seeks' shelves, gone; the merged report stays
```

The merge puts the seeks' claims to the judge in pairs: the same thing, a contradiction, or different
things. Claims the seeks agree on are filed as findings, with every seek's sources. Where they contradict
each other, both claims are filed as disputed and the question joins the open questions. What only one
seek found is listed in the merged report and not filed; that seek's own report has the evidence. The
report's Judgments section lists every pair the judge saw with its verdict and reason, and a reply the judge
could not be read for is listed as UNREAD and counts as nothing, so you can see what the merge rested on. A
seek's own ids carry its run's number, so none is ever the library's or another seek's: the first report seek
J-0126 writes is I-1260001, and the merged report has the library's next number. A
program does the same with `seek` on `library_research` and `library_seeks` (op=list, read, merge, drop).
A seek copies the library's findings, reports and pages when it starts, so on a large library each seek
takes a while to start and its share of disk.

## 24. Law and patents

Two fields for the texts a web engine buries under commentary. They join a run on their own when a
question is plainly about them, or when you ask with `--field law` or `--field patents`.

**Law** searches the courts' and the governments' own records: CourtListener (nine million US
decisions, federal and state), the eCFR and the Federal Register, Regulations.gov with a free key,
and Japan's e-Gov 法令検索 by a law's name and by the words of its articles. A citation in the question
is fetched, not searched: a reporter citation, a US Code or CFR section, a 法令番号. Every claim carries
the jurisdiction it holds in and the date the law stood, since law changes. State codes, city
ordinances and Japanese judgments have no search a program can make; the run finds them through a
web search and reads them page by page, and the Japanese courts' 裁判例検索 is listed as a site to
open. The report keeps what a text says apart from how courts have read it, and says what was not
found at a level. It is research, not advice. A run asked for with `--goal what-the-law-says`, or a question that asks whether
something is legal or what a law requires, reads the governing text itself through these collections or by its citation, opens its
report with that citation and the date it stood, and says which levels and courts were not searched.

**Patents** reads the offices' own records: the European Patent Office's Open Patent Services with its free
key, which hold the publications of most of the world's offices, and for other runs Google Patents without a key
or through SerpApi. A patent is read from its own publication by number: claims, dates, assignee, family,
status. A provisional application is never published; it shows as the priority claim of a later one, and its
text sits in that application's file wrapper. An application filed with a non-publication request
is unseen until grant, but its assignment record is public and any foreign family publishes at
eighteen months. Japan publishes every application at eighteen months. A collection whose monthly
allowance is small (SerpApi's 250 searches) is searched at most eight times in one run, by all the
run's workers together; the report's list of collections says when a run reached that, and says of a
collection that was asked and did not answer that it refused, not that it was not searched.

A prior-art run (`--goal prior-art`, or a question that asks whether something was patented or invented
before) does not search for patents. It enumerates them. The model finds the classification the invention
sits in, a CPC group such as A47G19/22, from the office's classification search, which answers with the
groups and everything that stands under them, or from a known patent's record, and gives the program three things: the classes, the years, and the invention's features, each as
the words that would name it in a title or an abstract, in every language it can. The program does the rest:

- **The pool.** Every publication of each class in those years, taken from the office page by page to the
  end. The office gives a hundred records a request and the first two thousand of a query, so a class and a
  span over that is split by year, then by month, then by day. The program says the office's own count, keeps
  one line a patent family, counts which features each family's titles and abstracts name, and orders the
  pool by more features named, then the rarer feature, then the older. Nothing popular is in the order: no
  citation count, no family size, no engine's rank. A class of a thousand publications takes two to five
  minutes and about ten megabytes of the office's free four gigabytes a week. Every page is kept in the
  library, so a second call fetches nothing.
- **The abstracts.** The model reads the abstract of every family in the pool, twenty to a call, and says
  yes, maybe or no for each feature. About ten seconds for twenty on a local model. Every reading is kept,
  so nothing is read twice, and a call the time cut goes on where it stopped.
- **The claims.** The claims of the families that pass, best first, from the office's own full text. The
  office holds claims for EP, WO and some thirty national offices, and none for the United States, China,
  Japan or Korea. For those the program reads the member of the same family whose text the office does
  hold, and says which. Where no member has one, it reads the claims from Google Patents' copy of the
  office's record, at the one address the publication has there, in the language the office published in.
  A page whose claims are Google's translation is not taken: the page in the language it was translated
  from is read in its place, or the original's words that the translated page keeps beside each claim.
  Google Patents is never searched and never asked to rank: the pool is the office's enumeration, and a
  page is read only by a number the program already holds, one page every three seconds at most, each page
  kept in the library. A publication with no claims at either is read by its abstract, and the report says
  so. For each feature the model names the closest claim, says whether it describes the feature itself or
  only partly, and quotes it; the program keeps the quotation only when its words stand in the claim as read.
  Each line gives the applicant the office's record names.
  A status that Google's page words, such as Pending or Granted, is given as Google's own reading and never
  as a fact of the report.

The report says the office's count, how many families were enumerated, how many abstracts were read, how
many families' claims were read from the office and how many from Google Patents' copy, for each feature the
closest publication with its claim quoted, where it was read and its link, or that none that was read meets
it, and what was not read. The library writes the same account under the
report from its own records of the run, and gives it to the model that writes the report, so the counts do
not depend on what a step noted before a later step read on, and checks the report's numbers against that
account as well as against the notes. Such a run is planned as one chain, has no patent
search that ranks on its table, and so cannot fall back to an engine's first page. When the model
that plans splits the chain into parts all the same, the program hands the parts to one worker in the
planner's order, so there is one pool and the abstracts are read once.

The same three steps are commands, a page (`/patents`), `/patents` in the chat, and MCP tools
(`library_patents`, `library_patent_abstracts`, `library_patent_claims`):

```
researchzosho records key epo-query <key> <secret>         # the EPO's free registration, at developers.epo.org
researchzosho patents pool A47G19/22 --from 2005 --to 2015 --feature "lid, cover, cap" --feature "straw, drinking tube"
researchzosho patents abstracts <the key the pool answered with>
researchzosho patents claims <the same key>
researchzosho patents claims EP1000000A1                   # what one patent claims, and its classes
```

```
researchzosho research ask "What does 37 CFR 1.53 require for a provisional filing?"
researchzosho research ask "特許法第三十五条の職務発明の対価はどう決まるか"
researchzosho research ask "Is there prior art before 1990 for a gear cut with a dividing plate?" --field patents
researchzosho records key courtlistener <token>            # optional: a free token raises the rate CourtListener allows
researchzosho records key regulations-gov <key>            # a free api.data.gov key
researchzosho records key epo-query <key> <secret>         # the EPO's free registration, for the office's own search
```

This version covers US federal law, US state case law and Japan's national law and courts. State
and city codes are read as pages. The UK and the EU, and search by company for unpublished
applications, come next.

## 25. People and organizations

A question about a person or an organisation is answered as a dossier: a fixed set of sections, each
claim carrying the class of its source and the date it held, and a Reading at the top written for the
leaning of the question. The shelves keep the facts without the leaning, so the same subject asked from
another angle reuses everything found. The fields join on their own when a question plainly asks for
one (a dossier, a background, "who is Name Name", a corporate history, a legal form such as Inc. or
株式会社, a register number), or with `--field person` or `--field organization`.

```
researchzosho research ask "Who is Neal Stephenson, and what is his record of working with companies?"
researchzosho research ask "Corporate history of Cisco Systems, Inc.: subsidiaries, officers over time, filings." --field organization
researchzosho research ask "高市早苗の経歴と人物像をまとめて"
researchzosho library add ~/exports/linkedin.json          # anything you already have: the run reads it as supplied
```

**Finding them.** The page `/dossier` lists every dossier the shelves hold, with its kind, the counts of claims
and reports, the latest run's day and whether its story is written; the Library menu leads there, below Map, and each
report's page links to the dossier of its subject. At the command line, `researchzosho dossiers` prints the
same list, and `researchzosho dossier "Name"` one dossier.

**Reading it in another language.** `researchzosho dossier "Name" --in en` prints the dossier in English, `--in ja` in
Japanese, any two-letter code for the rest; the page has a Read in link, and `library_dossier` takes `in`. It is the
same page and the same printout: the pieces of prose they are built from — the reports' sections, the claims' titles,
the analysis, the open questions — are translated by the model and kept under dossiers/ until the dossier changes, and
the page is built again from them with its badges, its links, its pictures and its tables as they are. First a glossary:
the names and terms that recur are rendered once and used the same in every piece. The cites in square brackets, the
claim ids, the dates and the addresses stand as they are, and a piece whose translation would have lost one is kept in
the original. The claims themselves keep their own language on the shelves: a claim quotes its source. On the page a
writer's visit starts the translation as a job, a few minutes on the local model; readers see it once made.

**The analysis layer.** After the facts, a report on a person or an organisation says what they add up to,
apart from them: Judgments, each sentence citing the claims it rests on; Conjectures, each with a likelihood
word and number from one table (almost no chance 1–5 %, very unlikely 5–20 %, unlikely 20–45 %, roughly
even chance 45–55 %, likely 55–80 %, very likely 80–95 %, almost certain 95–99 %), its confidence stated
apart and explained by the sources, the alternatives considered and what would change it; Forecasts, dated,
with a base rate; Implications and options for the leaning of the question; the questions that would change
the view, which join the open questions; and a source summary. The dossier shows the layer under Analysis.
Conjectures and forecasts never become claims on the shelves. The forecasts are kept on a ledger:

```
researchzosho forecasts                       # every dated forecast the reports made, open or scored
researchzosho forecasts "Acme Corp"           # one subject's
researchzosho forecast P-0003 happened "the second customer was announced in May"
```

Once a forecast's date has passed, score it: happened, did-not, or void when it cannot be judged. The Brier
score over the scored forecasts says how well the reports foresee (0 is perfect, 0.25 is coin-tossing).

**A person's dossier** has Identity (names, aliases, how the person was told from namesakes, and which
namesakes were set aside), What they do (the role now and what it produces, what they are known for, how the
doing changed, and what they say they are about in their own words, apart from how others describe them, since
for a person what they do says who they are), Timeline, Roles, Decisions (the principal moves of the career, dated, with the
reason the person gave in their own words), Holdings and filings, Material, Associations, Influences (who
and what shaped them, as they said it, and whom they shaped), Public record, Current with its date, and
Looked for and not found. Identity is resolved the way the genealogy
field resolves it, by handles: an employer and the years, a title, a place, a co-author, a book, an
account. Every fact found is a handle for the next search.

**An organisation's dossier** starts with the registry card (legal names over time, CIK, LEI, EIN,
法人番号, incorporation, addresses), then The work (each principal product, project or programme with its
own account), Customers, peers and alternatives (who pays, and for each work item who else does this and what
the customer would use instead, as the organisation names them and as the press does; none named is said so),
Structure, Power and governance (who holds power over it and
how it decides), People (each one a person with a handle, so their own dossier links), Decisions (what
the people in power decided, dated, with the reason each gave and the outcome), Filings as a timeline,
Money, Workforce and signals (headcount, hiring and departures, the cadence of filings and releases),
Products and marks, Footprint, Public record, Reputation over time, Current, and Looked for and not
found. The decisions become claims like the timeline's, and the dossier lists them apart. Both dossiers also carry
Statements, what the subject said in their own words, dated and placed, from interviews, talks, posts (Bluesky by
handle), press releases and testimony, listed on the dossier as "In their own words"; Media, the photographs, video
and scans of the subject as records with their provenance (who made each, when, where, under which licence) from
Wikimedia Commons, YouTube, the Internet Archive and the subject's own pages, shown with a thumbnail where the address
gives one; and a Pattern of life the library computes from the claims — what the record holds by year, the places it
names with their years, the appearances — a pattern of public traces, never a tracking. Each person a
report names under People leads to their own dossier, or to the run that starts one. In the chat, `/interview
Name` makes the library ask what it does not know about the subject, its open questions; what you answer is
filed as told, in your words. A watch's arrivals are suggested when they come, and a task that reaches the
library through a coding chat is not filed as a question. Japanese names and places are read like Latin ones:
高市早苗 links two dossiers, 千代田区 is a place and 高市 is not; the review files its claims in the record's language. The dossier page shows
the facets the reports carry under Facets, how it was made under a fold closed by default (the runs that fed it with their
dates, models and times, the claims by the class of their source and by site, what was told, the span of the claims' dates),
and under Refresh and watch a link that researches the latest question
again and a Watch button; `researchzosho dossier "Name" --watch` does the same, keeping a search for the subject every
seven days whose arrivals land in the inbox. `researchzosho vault` writes one note per dossier under Dossiers/, its
claim and report ids as links.

**Five source classes** are on every claim: public record (a register, a court, a filing, a
catalogue), own publication (the subject's own words, site or account), third-party (press and
reporting), supplied (what you gave the library), and canon (a fictional subject's own work, by
edition). The library marks each reference in the report's list with its class, read from where the
reference lives, never from the model's opinion; a claim's class is that of the references it cites. A fictional person or place has two dossiers that never mix: in-world, from the works, and
real-world: who created it, who holds the rights, adaptations, current status.

**Looking for something and not finding it is a finding.** The dossier says which register was
searched, with what words, as of what date. The collections searched are listed in the report by
the library, not by the model.

**The dossier is a view over the shelves,** not one run's report: everything the library holds on
the subject, from every run, every page you added and everything you told it, as one document.

```
researchzosho dossier "Neal Stephenson"                  # the relations, the claims by source class, a timeline, the reports to dive into
researchzosho tell "Cisco Systems" "We came in through the WebLine acquisition in 1999; most of the team was gone within two years."
```

The page is at `/dossier?subject=…` on the library's pages; a program asks `library_dossier`. Each
report listed is a page to dive into.

**The major things first.** A dossier opens with the milestones: for an organisation when and where it was founded,
under what name and by whom, when it was chartered or incorporated, renamed, merged or moved; for a person born when
and where, educated where, the roles in order. The run asks for them before any other facet, and the timeline lists
them first within a year. A line under the Timeline heading says which runs the timeline came from and whether the
founding and the basics are there yet; a run asked a narrow question, who holds power and how it decides, fills the
timeline with that run's decisions, and the full research adds the rest.

**A report's Timeline becomes claims.** A run notes a handful of claims; its report's Timeline carries
every dated event the workers found. When a dossier report lands, each event of that Timeline is filed
as its own claim, sourced by the references its sentence cites, so the dossier and the story hold what
the run found and not only what a worker noted. `story` does the same for the reports already there.

**The story.** `researchzosho story "<name>"` (a program: `library_dossier` with `op=story`) writes
the story of the subject as a report, chapter by chapter from the claims the library holds, a decade
a chapter with the undated claims first as background; every sentence cites the claim it rests on,
and what a person told the library is said to be theirs. It is listed on the dossier and is a page
to dive into. The story follows the shelves: when a later run on the subject lands, the story is
written again from the grown shelves and the earlier version stays as its own report; the dossier
says how many claims the story was written from and whether the shelves have grown since. The books are where the people and the
stories are: Google Books is searched inside the books, and HathiTrust and the Computer History
Museum's oral histories are links the run opens by hand.

**Telling the library.** What you know that the record does not, what it was like, who was there,
an incident, a date, is a claim of a sixth source class, told: in your words, dated, with you as
the writer. In the chat, say it and the librarian notes it ("noted: …") and asks you the next open
question on that subject, one at a time; a program uses `library_tell`. What you tell can open a
search: the people, dates, places and events it names that the record has not been asked about join
the open questions, and the librarian offers the research. The interview feeds the research, the
research feeds the story.

The collections: for people, Wikidata, OpenAlex and ORCID, SEC filings by the words in them, FEC
contributions (which carry employer and occupation), Japan's Diet record by words and by speaker,
Hacker News, Bluesky accounts, Qiita, NSF awards, the courts and the archives; for organisations,
GLEIF, SEC EDGAR, ProPublica's Form 990s, Senate lobbying disclosures, FDA device clearances, NSF
awards, the College Scorecard, the certificate log of a domain, gBizINFO and the 法人番号 register,
OpenCorporates, OpenSanctions, patents by applicant, GitHub. Most work without a key.

```
researchzosho records key gbizinfo <token>          # https://info.gbiz.go.jp/api/ (free)
researchzosho records key houjin-bangou <id>        # https://www.houjin-bangou.nta.go.jp/webapi/ (free)
researchzosho records key opensanctions <key>       # https://www.opensanctions.org/api/ (free for research)
researchzosho records key opencorporates <key>      # https://opencorporates.com/api_accounts/new (by request)
```

The api.data.gov key that serves Regulations.gov also serves the FEC and the College Scorecard.
Links the run opens by hand: 渋沢社史データベース, EDINET, USAspending, USPTO assignments and
trademarks, the ISFDB, the Copyright Office catalog, 官報.

This version covers the United States and Japan. The UK, the EU, time series over dated traces,
images with their provenance, and refreshing a dossier for what changed since, come next.

## 26. Suggestions

The library suggests what to do next from the shelves' own events, never from a model's free text: a
forecast whose date has passed and wants scoring, a story the shelves have grown past, a question you asked
that a claim now answers, a question asked again and again, a report that landed, a dossier not researched
for months. Each suggestion is classed now, next or hold, with the probability you take it up: the nightly
housekeeping asks the model's typed judge, one bounded answer per candidate; the page and the chat use
plain rules. The ones for now appear as a line at the top of Past and in the chat on your next visit.

```
researchzosho suggest                         # the open suggestions, now first, each with why, what to do and what it costs
researchzosho suggest taken S-0003            # or dismissed, or later: your mark is the outcome the library learns from
/suggest                                      # the same in the chat; the page /suggest has buttons
```

Your marks are kept against the probabilities given, and the Brier score over them says how well the library
reads you (0 is perfect, 0.25 is coin-tossing). `library_suggest` is the same for an agent.

## 26a. Sources: what the library mines

The places the library reads for the things a field looks for: registers, directories, boards, list pages, feeds,
saved searches. A search keeps the ones it reads, with what each yielded and for whom; one that yields nothing new
for a while goes dry and is read rarely; one you take off is never read again, however often a search finds it.
Add your own by address, with a word on what it is for, and the next search of that field reads it first.

```
researchzosho sources                          # every source, with what it yielded and for whom; then the hosts you trust or refuse
researchzosho sources add https://example.org/postdocs --for "neuroscience postdocs" --field career
researchzosho sources remove S-0003            # taken off: no search reads it until you bring it back
researchzosho sources restore S-0003
researchzosho sources show S-0003
```

A source earns its place by what its yield survives, never by having been found. Each one carries how many of the
things it yielded passed the first checks (an opening that passes, a company worth approaching, a like) and how many
fell (cut, dropped, passed on); one whose yield keeps falling goes doubtful, is not read first, and the page says so;
one survivor brings it back; a large yield never does. A host you refused is never read as a source and never kept,
whatever a search found there. The text of a page is evidence, never instruction: the names a page gives are read
as names, and what reads as an address or an instruction is dropped at the reader.

The Sources page shows the same list with the same buttons, and `/sources` in the chat takes the same words. The
hosts you trust or refuse for research runs (`sources trust`, `sources refuse`, `sources forget`) are shown with them.
Over the protocol it is `POST /v1/sources`, and over MCP `library_sources`.

Everything the library reads on its own is on this one list. The job boards the career search reads are there from
the start, each with the place it is for and a name, and one you take off is never read again; a board of your own
from the `career_boards` setting is there as yours. Your saved searches and the podcasts you follow are there too, as
`shelf-<name>`, with how often the nightly check runs each and what it has found so far; taking one off parks it,
bringing it back puts it in the rotation again, and `shelf add`, `shelf park` and the rest still work as before.
Words given to `sources add` instead of an address are a search to run again: they become a shelf.

## 26b. Looking for work

The Career page reads your working life and looks for what comes next. Nothing about you leaves the library.

More than one person can be kept: the Career page lists everyone whose working life the library holds, and its "A new person" form opens a page for a new one from their name and a resume, a LinkedIn file or their words; at the command line, `researchzosho career provide <file> --name "Their Name"` does the same from a resume or a LinkedIn file.

Hand over what you have: a resume (PDF, Word or plain text), your LinkedIn profile saved as a file, or a few
words about what you want. On the Career page, choose the file in the form and send it; at the command line:

```
researchzosho career provide ~/resume.pdf
researchzosho career provide "I want to stay in Boston and build hardware again"
researchzosho career reading        # the library's reading of your working life, and a first draft of what you want
researchzosho career interview      # its questions, one at a time; skip passes one, stop finishes another time
```

Words you give the library, in the form on your page, at the command line or in the chat, are read at once for what
they decide: a role, a kind of work, a place, remote or not, an industry you want or rule out, a company to look
at. What they say plainly is taken as decided, the page's "What changed lately" says what was set, and the next
search starts from it; the openings already found can be sorted again on the openings page with no new search.

A search started before any reading is written writes the reading first and drafts what you want from it, the
guesses it runs on until you decide. What you decided is the base, and a search can vary it for that run only. Beside "Look for openings" there is a box
for a change for this search: "without the pets field, and New Hampshire as well". In the chat, `/career search`
and the words; with several people, the name first and a colon, `/career search Jane Doe: without the pets field`;
at the command line, `researchzosho career search` and the words. The library reads the words against what you
decided into the topics as they stand for that search, makes that search's own cut, and runs on it; what you
decided, your keep-outs and the companies of your place keep their readings. The search is recorded with its words
and its own cut: the Searches table says what it changed, and its page judges the openings by that search's cut,
so the New Hampshire openings show there as results, not as set aside. The nightly watch runs on what you decided.
The field you decide, "anything to do with pets or animals", names the employers the search looks for: its web
searches, the registers' sections and the lists it keeps as sources are for that field beside your role and place.
First of all, a decided field has the library research the employers in it near your place, the way it answers any
question, a run of about eight minutes whose report is kept on the shelves with its sources and serves again for a
month; each organisation it names is checked by its hiring page before any opening of its is read.

The person's page opens with the search box, then their searches, newest first: when each ran, what counted as a fit, how many employers it
looked at and could read, how many openings passed and were read, and the employers it looked at behind a click; the
date opens what that search found, as it stands now, and says what that search changed. A new search throws nothing away: the openings stay with the days they were seen, and the lists show the present state.
Every section of the page, the reading, what you want, the questions and the rest, is folded and opens on a click,
so the page reads as its contents and the search box. An opening at an employer in the field you decided, named by
the library's research or read as worth approaching, comes before the rest of the openings that fit.
Where you work now, read from the role that runs to now, is left out of the search and its openings are set aside,
whatever source names it, unless you name it yourself.
An institution the search names, a hospital or a university, has its openings read from its own careers page or the
board of the organisation it belongs to; its town is matched however the board writes it, and a board that names
campuses rather than towns keeps its openings with a note saying so.

The reading says what you keep choosing, what seems to drive you, the places you work best in and where you
are now; each line points to the facts it rests on. It ends with questions, and the interview asks them one at
a time, saying first how many there are. Your answers are kept in your words. Have the reading written again
after answering and it asks a few more questions that go deeper into what you said; they join the same
interview as a new round, your earlier answers stay, and the search needs none of them answered.

What you want is kept topic by topic: the area you work in, the kind of work, the role, the kind of employer (a
company, a firm, a hospital, a lab, a school, a public body, an agency, clients of your own), the employer's size
and stage, the culture, the place, whether you would move or commute, remote, the arrangement (full-time,
part-time, contract, temporary), a licence or a visa to sort out, the pace, the pay and ownership. Each topic is
decided (you said it), a guess (the library read it from your history, for you to confirm) or open. Whatever you
hand over that rules something out is kept out from then on, and the people and employers you name are kept, for
the search. An answer sets a topic only when it says what you want next; an answer about the past sets nothing,
and a topic you decided keeps its words until you say it changed. Correct any of them on the Career page, or with
`career want remote decided yes, fully remote`; `career answers` has the library read every answer again for
what it settles.

The interview asks first for whatever the library needs to look for employers at all and your input left out:
what work and at what level, what kind of employer, where, whether you would move, remote or not, what
arrangement, a licence or a visa. One plain question each, in your language, only for the topics still open; the
deeper questions come after. Say "go" at any point and the search runs on what is decided and the guesses.

The interview ends with two questions: what else to look for, and what you do not want. Companies you name go
on the list the search looks at. Whatever you rule out, a company, an industry, a kind of work or a condition,
is kept out. `career company <name>` and `career keep-out <words>` add to either at any time.

### The search

```
researchzosho career search          # looks for openings, as one of the library's runs
researchzosho career openings        # what it found: the ones the library read first
researchzosho career like 3f2a9c1b07 "hardware, and close to home"
researchzosho career pass 77c0d2e9aa "another platform team"
researchzosho career keep Formlabs   # keep or drop a company on the list
researchzosho career cut             # what counts as a fit, and how to change it
researchzosho career watch on        # look again every night; watch now looks once, at once
```

A search lists the employers for you and reads their openings. First the ones you named and kept. Then the
employers the public records list for your work and your place: the company registers of your country, the lists
of hospitals, schools and universities, the filings of money raised, and the pages the library has found to list
such employers, each kept as a source you can see on the Sources page and take off. Then, when you decided a
place, the employers on the job boards for that place that are hiring for a title you want; the library knows a
few boards and `career_boards` adds others, and a board you add on the Sources page is read too. Then employers
hiring right now for the titles you want, found by searching the hiring systems' own job pages and kept only when
the employer's own board still lists the posting. Last, a few the model chooses among the employers the records
list in your place that the search has not put on the list, each with a sentence on why; it never names an
employer the records did not list, so a short list is normal, and none at all when the records give nothing.
The library reads each employer's openings from the employer's own hiring pages: the hiring systems that publish
them (Greenhouse, Lever, Ashby, Workable, Recruitee, Personio and Workday), the portals hospitals, universities
and public bodies hire through (a health system's own careers site, Oracle, iCIMS, Taleo, PeopleAdmin), or else
the job data or the job links on its careers page. A hospital, a school or a lab that a register listed with no
site of its own is read through the organisation it belongs to: the parent Wikidata names when it names one, else
the leading words of its name ("Ascension Seton Medical Center" gives Ascension Seton, then Ascension), each
searched for its careers page and the portal it leads to. The portal's openings are kept to your town, a board
that says nothing of a hospital, a school or a lab is a namesake's and is not taken, and the employer's line says
whose board the openings came from. The library also reads the month's Hacker News "Who is hiring" thread, and
USAJobs when you set a key for it (free at developer.usajobs.gov; the settings `usajobs_key` and
`usajobs_email`), when how jobs like yours are filled says those carry your trade. It never reads a job board.
Some hospitals and public bodies still keep their openings in systems the library does not read, or behind a
wall a plain fetch cannot pass, and then the openings page says so and names each employer for you to open
yourself. Every request names researchzosho.org, each site's robots rules are kept, and a site gets at most one
request a second.

What counts as a fit is what you decided: words a title must have (and, when what you decided has two sides, such
as a founding role that is an engineering one, a second set it must have as well), words that rule a title out,
places, and whether remote counts. The model turns your decided topics into those words once. After that the
sorting is plain matching, so thousands of openings are sorted in a moment, and the same openings always sort the
same way. A guess never drops an opening; it only puts some ahead of others, and a role beside an executive, a
field CTO or an office of the CEO, goes after the executives. Change what counts as a fit on the openings page, or
with `career cut places Boston, Cambridge, MA`, and the openings already read are sorted again at once, with no
new search.

Fit is judged with the company's dossier beside the posting, never from the posting's words alone. After a search
the library files a **company card** for the companies whose openings pass, three at a time (`career_cards`):
the dossier builder's own research run, bounded to about eight minutes and three questions, the fewest that
answer your wants: how many people work there and the stage; what it builds, where it is and who leads its
engineering; this year's hiring, layoffs and news. The card lands on the
same shelves as every dossier, each claim with its source and its class, and the company's website anchors the
run so that a namesake's facts never land on it. Once a card lands, the company's openings are read again with
it. When a card gives no size or stage, the opening and the company say so plainly, "Needs a full run to get its
size", with the button beside it, and a company with no card yet says that, with both buttons. The **full
dossier**, the deep run with the whole brief, is never automatic: a button, or `/career dossier <name>`, for a
company you are serious about. A card is not filed again within a month, and the nightly ones
only when your watch is on; `career_cards 0` turns them off. The headcount for the cut is read from the card's claims; failing a card, from
the company's own pages, its LinkedIn page as a search result shows it with the website it names, or Wikidata
when the website it records is the same, each with its source and date. With a size decided ("not bigger than
200"), a company known to be more than twice that is cut before anyone reads it, one that may be over goes
after those within, and a company whose own board lists a hundred openings is cut as well.

The model then reads the 40 openings nearest to what you want, at most three from one company before a fourth
from any, and writes three lines for each: what fits, what goes against what you want, and what the opening
does not say. That is the slow part, about a minute an opening on a local model, so a search takes half an hour
to three quarters of an hour. The rest are sorted by how closely each matches the role and the place, as you
decided them or, until you do, as the library guesses them: the ones that match both are listed open, by employer;
the near misses (the role elsewhere or remote, or the place with another role), the rest, and what your decisions
set aside, each with its reason, are closed with their counts and open with a click. A big employer's board shows
its first few and says how many more it has. Read more has the model read the next 40.

Like or pass on an opening, with why in your own words. Keep a company and every search looks at it; drop it
and no search shows it or proposes it again; keep it out and it joins what you rule out. Every opening shows
when it was posted, as the company says, and the day the library last read it. An opening that leaves its
company's list is marked gone.

With the watch on, the night's housekeeping reads your companies again, straight from the hiring pages the
search found, looks at the portfolio boards and the hiring sites again for companies new that night, reads the
month's Who is hiring; keeps the new openings that pass; and has the model read up to 10 of them. They wait at the top of the openings list under New since you last looked, and the inbox says how
many: one line, never one an opening.

The numbers are settings: `career_companies` (60), `career_readings` (40) and `career_watch_readings` (10). The same commands work in the
chat (`/career search`, `/career openings`, `/career like …`); on the Career page the button at the top of the
person's page, "Look for openings", starts it, and the page links to the openings list.

### Near a city

A place you decide as a city means the city and the towns near it, not the city's name alone: an opening in
Villeurbanne passes for Lyon, one in Somerville for Boston. The library asks Wikidata once for the towns within 30
km of each city you name, or the distance you give ("within 20 miles of Boston"), keeps the answer for a month, and
the log says which towns count. A state, a country or a code is no city and adds nothing.

### The companies of your place

The roles at the top of a company are seldom posted, so a search also starts from the companies: those in the
place you decided that the public records name, read against what you want. The records are free and need no
key. For a place in the United States, the SEC's Form D filings, which every company files when it raises money
from investors: the company, its city, how much it raised and when, whether it is less than five years old,
whether it has revenue yet, its industry, and the officers and directors it names, who are the way in. Everywhere,
Y Combinator's directory, through its open mirror: the company, its place, how many people, its batch and its
industries. And Wikidata's companies with their head office in the city, with their website, employees and
founding year where it has them. Funds, trusts and the other filers that are not companies are left out by their
names and their industry line. The records are read once a week, at the polite pace, and kept with your record.

```
researchzosho career companies       # the companies of your place, read against what you want
researchzosho career like Sentonix - a young company building its leadership
researchzosho career pass Acme - too far along
```

Each company's facts are shown with the record they came from and its date. The cut you decided applies first,
plainly: a company known to be more than twice the size you decided is out, and so is one in an industry you
keep out. Then each company that stands gets a reading in three lines and a word: what fits, what goes against,
what is unknown, and whether to approach now, maybe, or not. What the records answer is computed and named with
its source: the place, the size against what you decided, the money raised and when, the age, the revenue. The
model judges only what has no sensor, whether what the company does fits the field you want and whether its stage
fits the stage you want, each from that topic's facts alone, so a raise is never read as a size. A company is one
to approach now when nothing goes against what you decided and a record gives a reason, money raised within the
year, a company formed within five years or a small team, with a fit beside it; maybe when the records leave the
fit open. The companies page lists them under To approach now, Maybe, and the rest; like or pass on each with
why, and keep one to put it on the list the search reads for openings.
A company worth approaching joins the search's list on its own, its openings are read, and it gets a company
card after the ones with passing openings; the size and stage the card gives go back to the company's record,
and its reading is made again with them. A hiring board found for such a company's name is taken as its own only
when one of its postings names the record's city, state or one of its officers; a namesake's board, and the size
that would come from it, are left alone, and the company has no board until its card says more. The model's reading is made again only when what you want changes.
With the watch on, the records are read again each week, and new companies are read that night.

### The registers

Before it searches the web for pages that list employers, the search reads the company registers it knows for the
country of your place, each through the register's own route: in France the state's open search over the company
register, with no key; in the United Kingdom Companies House's search page, with no key, or its API when you set a free
key (`companies_house_key`, from the Companies House developer site); in Japan gBizINFO, when you set a free token
(`gbizinfo_token`, from its API registration page). Each read is recorded on the Sources page as a register with what
it yielded, and an employer from it carries the register's record in its reason: the town, what the register files it
under, its size when the register says, and the address of its record. Where the library has no register reader, the
web search runs as before, and a register it reaches is kept with no yield until a reader serves it. A register of your
own, one that lists employers on a page, goes in as any source does: `researchzosho sources add <address> --for
"employers" --field career`.

A hospital, a school, a university, a care home or a clinic is not on a company register. For those the library reads
the institution registers, by the kind of employer you decided or the one the search drew for your work, before the
company register, and each is a source like any other: in the United States the Medicare lists of hospitals, nursing
homes and home health agencies and the public school directory; in England the NHS organisation directory (hospitals,
GP practices, pharmacies, dental practices); in Brazil the register of health establishments (CNES); in France the
register of health and care establishments (FINESS), the school directory and the list of higher education and research
institutions; and, for any of these countries, the world list of universities. None needs a key. A kind of employer no
register covers in your country is said in the log, and the web search and the sources you add stand in for it;
restaurants, hotels, law firms and public bodies have no open register anywhere the library knows, so their lists come
from the associations' pages and the sources you add.

**The map, for the kinds no official register lists.** Restaurants, cafés and bars, hotels, law offices, accountancies,
architects, estate agencies, insurance offices, banks, shops, gyms, veterinary practices, libraries, museums, theatres,
public offices, childcare, workshops and charities are on no official register the library can read anywhere, and in
most countries neither are hospitals, clinics or schools. For these the search reads OpenStreetMap, the map made by
volunteers, through its Overpass service: the named places of that kind in your town, those with a website first, then
those of a brand or an operator, with the town, what the place is, and its website or the map's own record. It is read
after the official registers of your country, only for the kinds they did not list, kept a month, and asks for no key.
The map is as good as its mappers made it: a town's restaurants are mostly there, a law office often is not, and a
website is there for one place in ten. Every such employer's reason on your list says it came from OpenStreetMap, whose
data is the mappers' (© OpenStreetMap contributors).

Any employer's or institution's own board, with no person on the shelves: `researchzosho career board "<name>"` finds
its careers page, the hiring system behind it or the organisation it belongs to, and prints the openings; `/career` on
the Career page has the same form, and a program asks `library_board`.

## 26c. Reading Reddit and the Stack Exchange sites

A research run can read discussion threads in full, and so can you from the terminal: the threads of a subreddit, or of
any Stack Exchange site, for some words, best first, and then one thread with its answers or comments.

```
researchzosho forums r/selfhosted seafile nextcloud
researchzosho forums r/selfhosted seafile --days 365 --limit 5
researchzosho forums r/selfhosted read 1f3w5vs
researchzosho forums workplace counter offer salary
researchzosho forums softwareengineering monolith microservices when
researchzosho forums workplace read 192705
```

The site is `r/<name>` for a subreddit, or a Stack Exchange site's name: `workplace` (The Workplace),
`softwareengineering`, `stackoverflow`, `serverfault`, `superuser`, `askubuntu`, `security`, `dba`, `ux`, `academia`,
and the rest, the word before `.stackexchange.com` in the site's address. A thread's address works in place of its id.
In a research run the model has the same thing as the tool `forum_search`, and it uses it when a question is about what
people say to each other: how a negotiation went, what owners of a thing found, how a team solved a problem.

Where the text comes from, and what that means:

- **Reddit** is read through the Arctic Shift archive, a copy of Reddit that is minutes to hours behind, kept by one
  person, with no promise of uptime. Reddit's own pages are never read by the library: its terms forbid a program
  reading them, its feeds close on 2026-11-13 and its public API in March 2027. The archive searches one subreddit at a
  time, by words in the title or the opening post, and gives a thread's whole comment tree with the votes as archived.
  To point at Reddit and never read it, set `reddit_archive` to `off`. A Reddit app of your own (`reddit_client_id`
  and `reddit_client_secret`) still reads the "what owners wrote" kind of a shopping question through Reddit itself
  while Reddit's API lasts.
- **The Stack Exchange sites** are read through their own API: 300 calls a day without a key, enough for a few dives,
  and 10,000 with a free key from stackapps.com (`stackexchange_key`). The text is CC BY-SA, so a write-up cites the
  thread's address and the author's name, which every answer carries.

What these are for, and what they are not. A thread is what one person wrote at a time, and the votes are the crowd's
reading of it: good for what people say and do, how a negotiation went, what owners of a thing found after a year, how
a trade talks about its tools. It is not a source for a fact. A date, a number, a name or a claim met in a thread is a
lead, to be confirmed in a primary source, and the run is told so. The library files a claim that rests only on a forum
in the forum tier, which stays a draft for you to accept or dispute, the same as a claim from any forum page. The run is
told to weigh what people wrote and never count it, to quote people as people, and to cite the thread's address. A host
you refuse on the Sources page (`reddit.com`, or `workplace.stackexchange.com`) is not read.

### How jobs like yours are filled

Before it looks anywhere, a search draws how jobs for your work in your place are usually filled: posted
openly, through a board for the trade, through people who know people, after a licence or registration, or by
rounds and appointments. The library draws it from the role and the place you decided (else the title you hold and
where your record puts you), with the model; the code holds no table of occupations. What it drew is on the career
page under "How jobs like yours are filled", and at the top of the openings page: how jobs like yours are filled, what you need
(a licence by its name and the body that issues it), where such employers are listed, what says an employer needs
someone now, and what the search reads and skips. A nurse's search does not read Hacker News; a lawyer's reads the
bar's register among the lists and keeps the people you named first; a software engineer's reads the venture boards
and the hiring sites as before.

Your own word outranks it. On the career page, type how jobs like yours are filled in the box under the paragraph; at
the command line, `researchzosho career shape every job I had came through someone I knew`; in the chat, `/career
shape` and the words. The library draws the shape again with your words, keeps them, and the next search follows
them. `career shape` alone shows what stands.

The library also reads your reactions back: the openings you liked and passed on, with why, go to the model as
examples when it reads the next opening, the liked ones first.

## 26d. Looking for a home

The library can look for a home for you, in the United States first: a house or an apartment to buy or to rent. It is
the same shape as looking for work: a record of what you decided, a search that reads every source the library can
read, a page of what fits, and a card per home from the records.

Or say it in the chat in your own words, "find me a house to buy in Somerville, three bedrooms, under $900,000": the
Librarian makes the cut from your words, files the search as a run you can follow beside the chat, and when it lands
the Home page shows the fits in tiers with a link on each, and a report of the fits, every home linked on its line,
sits on Past.

Start on the Home search page (under "all pages" in the header), or in any chat:

```
/home start Our move
/home tell We want to buy a 3-bedroom house in Somerville, MA or the next towns over, up to $900,000, with a yard; nothing on a main road.
/home search
/home listings
```

`tell` is your own words; the library reads them for the topics they settle (where, how far from there, buy or rent,
the budget, bedrooms, bathrooms, the size, the lot, when it was built, the kind of home, the commute, when, must-haves)
and sets each as decided or as a guess. A decision you made is never undone by a hedge. `want` sets one topic
yourself: `/home want place decided Somerville, MA`. `keep-out` keeps a street, a kind of home or a condition out of
the search. Only what is decided keeps a listing or drops it; a guess puts them in order. The page shows every topic
with a form, and your words in a box.

The budget, the rooms, the size, the lot and the year built are each a range, with one end or both: "$400,000 to
$550,000", "under $500,000", "around $450,000" (fifteen per cent either way), "3 to 4 bedrooms" or "three or four", "2 or more baths",
"1,200 to 1,800 sq ft", "a quarter acre", "built after 1950". A bare sum is the most you would pay; a bare count is
the fewest you want. A home outside the range at either end is set aside, with the reason. The listings that fit are
ordered by closeness to your budget: the middle of a range, or the ceiling from below. The budget can also be a price
per square foot or per square metre ("under $850 a square foot", "$600 to $800 per square foot"), never read as a
total: a listing is judged by its price over its size when it gives both, and with a size decided too, RentCast is
asked for no more than the most per square foot times the largest size.

How far from the town still counts is 10 km unless you say ("within 5 miles", "15 km"). The library finds where the
town is on the map, and a listing within that distance of it counts as the place, whatever its town is called. So does
a listing in a town whose centre is within that distance, whatever the town's size: Boston counts for Cambridge,
Massachusetts, and the county or the metropolitan area around Cambridge does not. A place
is a town with its state, province or country, "Cambridge, Massachusetts" or "Cambridge, MA", so a listing in a town of
that name elsewhere is set aside with the reason ("it is in Cambridge, Maryland, not Cambridge, Massachusetts"); a
state alone counts the whole state when you say so ("anywhere in Massachusetts"). A state written once after a list of
towns goes with each town that has a namesake in that state, as in "Somerville, Medford or Cambridge, MA"; a town with
none keeps its own state, so "Boston or Providence, RI" means Boston, Massachusetts, and the log says so.

The search reads the listings from every source the library can read and says, on the Sources page, what each gave:

- RentCast, when you set a key (`RESEARCHZOSHO_RENTCAST_KEY` on the Settings page): the active listings for sale or for
  rent that fit what you decided. The budget, the rooms, the size, the lot, the year, the kind and the radius go to
  RentCast as its own filters, so one call brings the homes that fit rather than the first five hundred in the town.
  The first fifty calls a month are free, then each is paid; the search makes one call per kind of listing, and
  `researchzosho search status` shows the month's count. A call brings five hundred at most; when more fit, the page
  and the log say the search is wide and how many RentCast holds, and you choose: narrow the radius, the budget or the
  kind and search again, or fetch the next five hundred with `/home search --more` or the button on the page, one
  call each. A search never pages on its own.
- The search engines' own snippets of the portals' pages, as leads: an address, a price, the beds, and a link for you
  to open. The library reads the snippet and never the portal's page; no portal (Zillow, Redfin, Realtor.com, Trulia,
  Apartments.com) lets a program read it, with or without a key. A lead from a home's own page links that page; a lead
  from a page of listings links a web search for its full address, with "seen on" and the page of listings beside it.
- The estate agents' and builders' own sites in the town, found through the map: every address with a price on their
  front page. A site drawn by scripts cannot be read, and the page says so. An agent the map names with no website of
  its own is not read, and the log says how many there were.
- What you save yourself: a line as the portal shows it, pasted into the box on the page, or `/home add` and the line.
- HUD's list of the homes the government sells, and each portal's search page for your town, as links to open yourself.

What fits is shown in tiers: the ones in the place that show everything you decided and fit it, then the near misses,
each saying what it does not show ("price per square foot not known: the price is not given"), never a fit and never set
aside for it; the ones set aside say why. The near misses that show the most of what you decided come first, then the
ones nearest your budget, and the cards go to them in that order after the fits. Like or pass on each, with why. A short name in brackets stands for each listing in
the chat: `/home like [a1b2c3] the yard`.

For the three listings that fit best the search files a card, only for a listing that shows it is in your place, and
`/home card [a1b2c3]` files one on demand: the
library reads the records first, each with its source and the day, and says what it could not read rather than
guessing: where the home is (the Census geocoder), the flood zone (FEMA), the schools and the transit stops around it
(the federal school directory, the GTFS feeds), and what was paid for homes on the street and the permits there, from
the city's or the county's own records. With a Census key (`RESEARCHZOSHO_CENSUS_KEY`, free from
api.census.gov/data/key_signup.html) the card also gives the tract's figures from the American Community Survey, the
median household income and age, the owners' and renters' shares, the median home value and rent; without one it says so. New York, Boston and Pittsburgh have readers written for them; any other city
is read by the platform it publishes on. Most cities and counties put their assessor's rolls, their sales and their
permits on one of three, Socrata, ArcGIS or CKAN, each with one API: the library finds the city's, the county's and
the state's own portals first (their addresses follow a few patterns, and a web search names the rest), asks each
for its datasets, reads which columns hold the address and the figures off the column names and the first rows, asks
each candidate for the home's street, and keeps the one that answers, for a month. A permits dataset must be building
permits, by its name and by the work its rows describe, and a sales dataset the assessor's values or market sales, not
a tax sale; a dataset whose rows stop two or more years back loses to a current one, and when it is all there is the
card says "last updated" with the year. Seven states publish one parcel layer for the whole state with values or
sales on it (Maryland, Massachusetts, Nebraska, North Carolina, Washington, Wisconsin, and the sales ones first), read
when the city and the county give nothing. New York, Boston, Pittsburgh and Philadelphia keep readers written for them,
by county. Every card's records line says in one sentence which source answered, with its platform and how new its rows are, or
that no source is known for the place and what the library looked at.

A county the platforms and the state's layer give nothing for, no sales or no permits, is researched once a month, on the
library's model: a research run of the library's own asks where the county publishes its records, its report lands on the
shelves like any other, and every web address the answer names is tried by its shape, a dataset on one of the platforms, or a
file, a CSV, a TSV, a JSON or GeoJSON file, a zipped one, fetched once into the library and read for the street, with the
columns settled from its header and first rows as for any dataset. A county that publishes its records only through a lookup
page, one address at a time, is not read, and the card names the page for you to open. With no model configured nothing is
researched, and the card says what the library looked at.
Then a research run on the
library's model, about eight minutes, for the street, the neighbourhood and the press, with the records handed to it
as given. The run's report lands on the Dossiers page; the records read stand on the Home page under Cards.

The field is for the United States and for Japan in this version; the sources, the records and the portal links are those two
countries'.

In Japan, say the place as a municipality with its prefecture, "東京都杉並区", "杉並区, 東京都" or "Suginami, Tokyo", and the rest
as a Japanese ask writes it: "杉並区で2LDK以上、家賃15万円以下、駅徒歩10分以内の賃貸", "三鷹市で中古マンションを購入、5000万円まで、60㎡以上、
築20年以内、2階以上". The program reads Japanese words itself, with no model: 賃貸 or 購入, the budget in 万円 and 億円 ("15万円以下",
"4000万〜6000万円", "15万円前後"), the 間取り as rooms ("2LDK以上" is two rooms or more beside the living, dining and kitchen; "1LDK以下"),
the size in ㎡ or 坪, the age as 築N年以内, the walk to the station as 駅徒歩N分以内, the floor as N階以上, the kind (マンション, アパート,
一戸建て, 土地), and a station named with the place ("荻窪駅"). A price per ㎡ or per 坪 ("㎡単価80万円以下", "坪単価300万円以下") is its
own range, never a budget. An English ask about a place in Japan ("buying a flat in Suginami, Tokyo under 60 million yen, 2 or more
bedrooms") is read by the model as elsewhere, and the cut, the verdicts and the report come back in the language asked, the prices in
万円 for a Japanese ask and in yen for an English one, with the price per ㎡ beside a home for sale. The municipality is looked up in
Wikidata by its name, in the prefecture when one is said: its five-digit code, which the portals and the ministry key a town by, its
centre, and the municipalities within the radius (ten kilometres unless you say), which count as the place too; a ward of a designated city
is written with its city, "横浜市中区". The listings come from SUUMO, whose terms (read 2026-11-05) keep its content to a person's own
private use and forbid commercial use, and name no rule against a program, and whose robots file allows its rent list and its area
pages of homes for sale (the sale search list it keeps from programs, so the library goes by the area page of the municipality, which
takes the same filters): the library asks SUUMO's own list for the municipality with your conditions as its own filters, the rent or
price, the 間取り, the walk, the age, the size and the kind, and reads every page of the result, fifty rentals or a hundred homes a page,
one page a second, never a recommended or ranked slice; each room or home links its own page on SUUMO, with the list page it was seen on beside it. AtHome answers a program with a
verification page after one request and LIFULL HOME'S refuses programs at the door, so both are links for you to open, built for the
municipality. A card in Japan reads the ministry's 不動産情報ライブラリ (MLIT) with a key of your own (`RESEARCHZOSHO_REINFOLIB_KEY`, free
by application on reinfolib.mlit.go.jp, about five working days; its terms, read 2026-11-05, set no numeric limit, ask that calls be
spaced out and the key kept secret, and that a service using the API show the sentence「このサービスは、国土交通省の不動産情報ライブラリのAPI機能
を使用していますが、提供情報の最新性、正確性、完全性等が保証されたものではありません」, which every card carries): the prices paid for homes of
the kind in the same district over the latest four quarters with records (不動産取引価格情報, fetched once per municipality and quarter and
kept with the library), with the middle price per ㎡ and the home's own asking price per ㎡ against it; the nearest 地価公示 point with its
price per ㎡, its change on the year and its station; and whether the home's point lies in a flood inundation area and how deep, a sediment
disaster warning area or a disaster danger zone. Where the home is comes from the Geospatial Information Authority's address search, which
needs no key. Without the key the search reads the listings alone and says so in one sentence. `researchzosho search status` counts the
ministry's calls as "reinfolib".

The records of any address, with no search started: `researchzosho home records "108 Example St, Cambridge, MA"` prints the
line that says which source answered, the sales or values and the permits on the street, and the tract's figures when a
Census key is set; the Home page has the same form, and a program asks `library_records`.

## 26e. How the Librarian decides

Every turn of the chat and every step of a research run is a decision the model makes over one table of instruments:
search in a place, open an address, look at a video's frames, the candidates for a video and their sift, the pool of a
class of patents with its abstracts and claims, a home's records, the home search, an employer's board,
the library's dossier, who is who in the family, the shelf, a note, the write-up, and a question for you. Each
instrument's words say what it answers, when it pays over the others and what it costs, so the model can weigh a
look at three videos against a look at thirty. The old tool names still work beside the grouped ones.

A decision has a weight. Light ones, which hit to open next, whether a step is done, go to the model that does the
reading. Heavy ones, the plan, the critic's gaps, the write-up, and whether a side matter a step met is pursued now or
filed for later, go to the judge when a second model is set (`RESEARCHZOSHO_JUDGE_DRIVE` and `RESEARCHZOSHO_JUDGE_MODEL`),
so a hosted model can take the heavy decisions while a model on your own computer does the reading. Each seat's words
follow its own model's size: a small model gets a short table and complete calls to copy; a large one gets the full
table with the reasons.

A run may grow a sub-goal: when it meets a legal matter, a patent, a person or a home that bears on the ask, a worker
may pursue it as a sub-question of the next round under that goal's rules, or file it as an open question for later,
and the report's open questions say which. A claim about a law, a patent or the scientific record is written by that
subject's convention whatever the goal: a law with its jurisdiction and the date it stood, a patent with its number and
office, a paper with its DOI; a side matter pursued mid-run brings its convention with it. A question only you can answer is filed with the report too; a run does
not stop to ask. Every decision is in the run's trace with the model's reasons.

A question of prior art goes the same way as a video you remember: the model does not search, the program
enumerates. The model gives the classification, the years and the invention's features; `patents` takes every
publication of the class from the patent office's own records and orders the families by the features their
titles and abstracts name; `patent_abstracts` reads the abstracts; `patent_claims` reads the claims from the
office's full text, or from Google Patents' copy of the record where the office holds none, and quotes each
feature's closest claim with where it was read. These three are on the table of a run about patents
and of the chat, and the patent searches that rank are off the table of such a run.

The words every table carries say four more things, learned from a bench of a hundred decisions: a result already in hand
is used, not fetched again; what the library has read is searched on the shelves, and YouTube is the videos place; a choice
is a call, never a sentence about what to do next; and a video you remember but cannot name begins with the candidate
search, which the model gives the thing's name in each language and what you remember in your own words, never a search, goes on with the sift, which
judges every candidate's thumbnail and first sheet and hands back the survivors, and then with a look, sparse, at the
first five survivors and then the next five, so the answer says which second each remembered thing was seen at.
A model that answers in words where a call was asked for, or asks a question without words, is asked once more and then
taken at its word.

## 27. Not yet available

- Sharing one library between two households.
- Search reranking. It is built but off, because it did not improve results at this size.
