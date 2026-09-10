<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/brand/emblem-dark-512.png">
  <img alt="" src="docs/brand/emblem-512.png" width="220">
</picture>

# ResearchZosho

研究蔵書, *the research holdings.* The Research Harness For The Rest Of Us.

Three people, one problem.

Your vet mentions a new drug for your dog's cancer and says the follow-up is in three days. You want
to know what the studies actually say: how well it works, for which breeds, and what the side
effects are. The answer is spread across twenty papers, a regulator's review, and a lot of pages
that only look like medical advice.

You are tracing your family. Your great-grandfather appears in the 1911 census in Osaka, and by 1921
he is gone from every record the family knows about. Somewhere there are ship manifests, a
prefecture's emigration lists, and an old newspaper notice. Most of them are in Japanese, and none
of them are on the first page of a search.

You are writing one chapter of a thesis on the influence of the Soviet-Afghan War on 1990s British
science fiction. You have forty papers and articles saved, in three different languages, and one
week to work out what they agree on, where they disagree, and what nobody has looked at yet.

None of that can be read in one evening. That is the job ResearchZosho does. You give it the
question. It reads, notes each fact with the source it came from, and when it is
done there is a write-up waiting: what it found, where the sources disagree, and what it could
not resolve. Every claim in it points at a source you can reference. Then it keeps what it found, so the
next time you ask about the same thing you are not starting from zero. And if a paper you leaned on
gets a new version, or two of your own findings contradict each other, it tells you.

The principle is simple. Nothing counts as valid until it has been reviewed by you.

It runs on your own computer with a model you pick, local or rented. It is free and open source, and
it is for anyone who needs some reliable research done.

This is The Research Harness For The Rest Of Us.

→ **[LIBRARIAN_HOWTOUSE.md](docs/LIBRARIAN_HOWTOUSE.md)**: how to use it day to day
→ **[LIBRARY_PROTOCOL.md](docs/LIBRARY_PROTOCOL.md)**: for programmers, how a program talks to it

---

## What you can do with it

**Send it a question.** It works out what needs answering, reads in parallel (the web, and the
scholarly literature by DOI through Crossref and OpenAlex), and writes it up in sections. It keeps going until the job is done, or as long as you allow it. It
will also attempt to read sources in different languages, especially when it detects a non-english
language may be associated with the question - it will state which languages its sources were in.

**Check the answer.** At the end of every write-up there is a table of the facts it used, each with
its source and a quote, and a numbered list of unique sources. Before the write-up is shelved, each
sentence that cites a source is re-checked against the source referenced. If there is an issue that
is called out.  If the service is unable to read the source (due to a paywall, login, or other issue),
that source will be provided as a list for you to see if you can provide the data in question.

**Keep bad sources out.** A claim with one source stays a draft until a second, independent one backs
it. A retracted paper disputes the claims that cite it. A site you ban will not be used, and a
site you trust counts as a primary source.

**Decide what stands.** Each claim starts as a draft. You accept it, dispute it, or retire it. What
you accept is what the library uses next time.

**Ask what you already know.** `researchzosho ask "…"` shows you what the library has on a
question: each claim, where it came from, and whether it is accepted or still in dispute.

**Bring your own documents.** A PDF, a web page, or a whole folder. Point it at the folder and every
document in it is shelved as a collection. Then ask a question that researches only within those
documents, or one that starts there and goes to the web afterwards for more data.

**Ongoing research, every night.** Every night it checks the searches you have declared ongoing,
it looks into questions it could not answer before, re-reads a few accepted claims against their
sources, notices new versions of papers you cite, and keeps a dated backup. On Sundays it leaves
you two lists: claims that look like duplicates, and documents to consider cleaning out. It never
merges, deletes or decides for you.

**See how things connect.** When a claim says a person lived in a place, or an author wrote a work,
the library remembers the connection. `researchzosho map "Arthur Ellis"` shows everything around a
name, and the service draws it in your browser. Making connections across the research you have
accumulated.

**Open it in a browser.** The service has basic pages: search, ask, read a claim with its sources,
walk the subjects, see what changed and what is running, send a question, and download any write-up
as Markdown or PDF. Or, if you want a desktop editor, `researchzosho vault` writes a folder that
Obsidian, SoloMD or SilverBullet can import

**Let other programs use it.** Claude Code, Codex, Gemini CLI and any other MCP host can ask the library directly.
There is a small HTTP service with Python and Java clients for everything else.

## Getting started

You need Java 21 or newer, and a model server that speaks the OpenAI chat API for the research runs:
a local one (llama.cpp, Ollama, LM Studio) or a hosted API with a key (OpenAI, DeepSeek, Gemini,
OpenRouter and others). For research runs, a web search backend, which matters as much as the model: a Brave Search
API key (free plan), or SearXNG (setup starts one with Docker). With neither, the built-in fallback
searches Wikipedia and the scholarly literature only. Querying what the library already has works
without a model.

```
git clone https://github.com/Wyrdsekai/researchzosho.git
cd researchzosho
bin/researchzosho setup
```

When it is done, open `http://127.0.0.1:4649/` in a browser.

`setup` asks a few questions, each with an answer already filled in. Where the library installs. Which
model it uses, which it will check for. Which web search backend. Whether search is by meaning and/or
keyword. Whether to run it as a service. Which programs to connect. Then it shelves a document you name and answers a question
about it.  Run it again any time to change one thing; `--yes` takes every default.

The release will install on Linux, macOS and Windows. If you already use
CodeZaiku, `codezaiku install researchzosho` fetches the release, checks it, and runs setup.

Settings live in `~/.researchzosho/config` as `key = value` lines, or in the environment. The ones
you are most likely to touch:

| setting | what it is |
|---|---|
| `RESEARCHZOSHO_DRIVE` | the model server, for example `http://localhost:8080` or `https://api.openai.com/v1` |
| `RESEARCHZOSHO_MODEL` | the model name to ask for |
| `RESEARCHZOSHO_JUDGE_DRIVE` / `RESEARCHZOSHO_JUDGE_MODEL` | a second, stronger model for the judgment steps (planning, the critic, the write-up, the citation check), while a local model does the reading |
| `RESEARCHZOSHO_EMBED` | an embeddings server (OpenAI embeddings call), so search works by meaning as well as by words; `off` for words only. `researchzosho embed start` runs one with Docker (Text Embeddings Inference, about nine times faster than llama.cpp on the same model) |
| `RESEARCHZOSHO_LIBRARY` | where the library folder is (default `~/researchzosho-library`) |
| `research.workers` / `research.pause` / `research.window` | how research runs share the model: how many questions at once, a pause switch, the hours it may work. Set with `researchzosho research …`; they take effect at once |
| `RESEARCHZOSHO_BRAVE_KEY` / `RESEARCHZOSHO_SEARXNG` | the web search backend: a Brave Search API key (used first), a SearXNG address (default `http://localhost:8888`; `researchzosho search start` runs one with Docker); with neither, the built-in fallback, Wikipedia plus Crossref and OpenAlex (`RESEARCHZOSHO_FALLBACK_SEARCH=off` turns it off) |
| `RESEARCHZOSHO_API_KEY` | the key for a hosted API; it is sent only to that server |
| `RESEARCHZOSHO_UPDATE` | `check` (default): say when a newer release exists; `auto`: the service updates itself after the housekeeping when idle; `off` |
| `RESEARCHZOSHO_EXPLORER_PER_NIGHT` / `RESEARCHZOSHO_EXPLORER_TYPES` | how many open questions the housekeeping researches a night (default 2), and of which types (default report, asked, person) |
| `RESEARCHZOSHO_FETCH_PRIVATE` | `deny` to stop it fetching addresses on your own network |
| `RESEARCHZOSHO_FETCH_MAX_BYTES` | the largest document it will download (default 25 MB) |

### As a service

```
bin/researchzosho service install           # Linux, macOS or Windows; a user service, no administrator rights
```

The service listens on `127.0.0.1:4649`, does the housekeeping at 03:00, and runs the questions you send.
`service install --host 0.0.0.0` opens it to your own network. The pages are open as shipped: anyone who
can reach them can read and send questions. `researchzosho web signin on` turns on access control.

### From Claude Code, Codex or Gemini CLI

```
claude mcp add --transport http librarian http://127.0.0.1:4649/rpc --header "Authorization: Bearer <token>"
codex mcp add librarian -- researchzosho mcp
gemini mcp add -t http librarian http://127.0.0.1:4649/rpc -H "Authorization: Bearer <token>"
```

`researchzosho reader token <did>` makes the token. Every session can then use the library.

## What to use it with

Nothing to buy. In this order:

- **A browser.** Open `http://127.0.0.1:4649/` once the service runs. Search, ask, read a claim
  with its sources, have it explained, see the map, send a question, download a write-up. Nothing
  to install, and the way to let the rest of the house use the library.
- **Obsidian or SoloMD**, open the folder `researchzosho vault`
- **`codezaiku chat`**, to talk to the library on a local model, for free. It connects to the
  service: what the library holds is pushed into each turn, `/librarian` asks it, and `/research`
  files runs with it.
- **Claude Code, Codex or Gemini CLI**, if you have one. `setup` connects it; every session then has
  the library's tools. Any other program that speaks MCP connects the same way.

For programs there are two clients, both following [LIBRARY_PROTOCOL.md](docs/LIBRARY_PROTOCOL.md),
whose sections 1 to 6 are the contract and keep working across releases:

- **Python** (`sdk/python`, no dependencies): `pip install researchzosho`, then
  `Librarian("http://127.0.0.1:4649", token=…)`.
- **Java** (`client/`): `org.researchzosho:client`, then `LibrarianClient`.

## Licence

Apache 2.0. See [LICENSE](LICENSE). ResearchZosho includes no model. You supply a model server and
accept its licence separately.
