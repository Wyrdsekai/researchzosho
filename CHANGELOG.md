# Changelog

## 0.4.6

This release fixes `researchzosho setup` choosing a model that cannot chat, and the read-only check for SQL Server.

### Fixed

- `setup`: when a model server has more than one model, setup asked "Which model?" with the first name in the server's list as the default. Servers list their models in alphabetical order, so on a server with an embedding model the default was `embed`. Pressing Enter kept it, and the library could not answer a question. Setup now says what the choice is, shows the models numbered, marks the ones that look like embedding or ranking models, and offers the model already in your settings as the default, or else the first chat model.
- `setup`: a model that does not answer the hello test is not kept while another model is left to try. Setup says why and asks again. A server with one model asks nothing, and a server that lists none asks for the model's name.
- A model server address with `/v1` on the end, such as `https://api.openai.com/v1`, now works, in setup and in the settings. Setup's own example is such an address, and requests went to `/v1/v1/…`.
- SQL Server runs a second statement with no semicolon in front of it, for example `SELECT 1 SHUTDOWN`. The read-only check allowed this, because it only refused a second statement after a semicolon. With an admin login, such a statement could stop the server, kill a session or write a backup file. The check now refuses SQL Server's statement words anywhere in a query: SHUTDOWN, KILL, BACKUP, RESTORE, DBCC, RECONFIGURE, WAITFOR, USE, SET, DECLARE, BEGIN, COMMIT and others, and the locking hints UPDLOCK, XLOCK and TABLOCKX. A column with one of those names still works when you write it as `[use]` or `"use"`. `OPENQUERY` and `OPENXML` are refused too. A login that can only read was never able to run these statements. The other database kinds need a semicolon between statements and were not affected.

## 0.4.5

This release lets the library read your databases. You add a connection, and research runs and the chat can query it, read-only. It also makes the downloads smaller.

### Added

- `researchzosho db add <name> <address>` gives the library read-only access to a database. SQLite, PostgreSQL and MySQL or MariaDB work out of the box. SQL Server, MongoDB and DuckDB are downloaded when you ask: `researchzosho db driver install <kind>`. Each download is checked against a fixed checksum. DuckDB also reads a folder of CSV, Parquet or JSON files as tables.
- `db list`, `db schema <name>`, `db query <name> "<sql>"` and `db remove <name>`. For MongoDB, `db query <name> --collection <c>` with `--filter` or `--pipeline`.
- Access is read-only in four ways. Use a database user that can only read. The connection is opened read-only. Every statement is checked: one statement, and it must start with SELECT, WITH, SHOW, EXPLAIN or DESCRIBE. Nothing is ever committed. For MongoDB, only `find` and reading pipeline stages are allowed. DuckDB's access to other files is turned off once your data is loaded.
- The address and password are kept in `~/.researchzosho/databases.json`, not in the library. The password is never shown and never sent to the model. If you leave the password out of the address, you are asked for it.
- `--hide email,customers.ssn` hides the values of those columns in every schema and every result.
- A query returns at most 500 rows, 100 by default. Each result is saved as a page, so a report can cite it and the citation check can read a claim against the rows.
- Research runs and the chat get two tools when a database exists: one for the schema, one for a query. Only the library owner's questions get them. Over MCP and HTTP it is `library_db`.
- `researchzosho survey db:<name>` reads a database's schema, saves one draft claim on what it records, and lists research questions the data could answer.
- If your model is hosted elsewhere, `db add` and `db list` warn that rows will be sent there.

### Changed

- The downloads are smaller. Each tarball keeps only the SQLite native code its platform needs. The plain tarball went from 29.5 MB to 22 MB and each platform tarball lost about 10 MB, with the two new database drivers included.

### Fixed

- Reading a Calibre `metadata.db` now works on Windows. It failed there since 0.4.1 because the file address was built in a form SQLite does not accept for a drive path such as `C:\`.
- The Runs page and `researchzosho jobs` no longer fail when a run finishes at the moment the list of running jobs is read.

## 0.4.4

This release makes the chat follow the research runs it starts, so you no longer have to ask how a run is going. It will also properly select a file referenced by the questioner. It will also update earlier library versions to the latest version.

### Added

- The chat follows every research run it starts. The terminal chat prints a line when a run moves to a new stage, for example "Reading, 3 of 8 parts done · 12 min elapsed · about 40% done". When the run is done it prints one notice with the report's id. Say "show it" to read the answer. `/runs` shows the followed runs at any time. If you close the chat, the notice is there when you resume the conversation.
- The web Chat page shows the same line for each followed run, with a progress bar, above the input box. It refreshes in place, so what you are typing stays. A finished run links to its report.
- The Runs page, each run's own page, and "Running now" on the home page show the stage in plain words, the time elapsed, and about how far along the run is. `researchzosho jobs <id>` prints the same.
- A question can name a file or a Calibre library on the machine where the library runs. The library reads it in before the run starts, because a research run cannot open files or run commands. A Calibre library or its `metadata.db` becomes a list, so the run can look up what you own. Any other document is added. Paths with spaces work. Only the library owner's questions do this.
- `researchzosho repair` fixes what an earlier version saved wrongly, such as a database or a PDF saved as unreadable text. If the original file is still on disk, it is read again and the unreadable copy is removed. The service runs this by itself once after each update.
- The percent is the larger of two numbers. One comes from the stage the run is in. The other comes from the clock: against the run's time limit when it has one, otherwise against how long research runs usually take in this library. It never reads 100 until the run is done. The terminal chat also prints the line every five minutes, so a long stage is not silent.
- The terminal chat has a line editor. The up and down arrows go through what you typed before, in this and earlier sessions. Ctrl-R searches it. Ctrl-C clears the line. Ctrl-D leaves. Run notices appear above the prompt and do not disturb what you are typing. The history is kept in the library under `catalog/chat/history`. When input is a pipe, the chat reads plain lines as before.

### Fixed

- A research run no longer fails when a citation in square brackets sits inside a parenthetical citation, as in "(Field 1866, and see [3])". That lost a finished report at the last step.
- If the citation check itself fails, the report is kept and says that its citations are unchecked. The run no longer fails.
- A research run's page reader refuses a saved page that is unreadable binary and says so, instead of reading it page by page.
- Reading a file in again replaces an older saved copy of it that is unreadable.

## 0.4.3

This release rewrites the text on the web pages and in the command line in plain words. It changes no commands and no flags.

### Changed

- The web pages and the command line use plain words. "Shelves" is now "the library", "patron" is "user", "write-up" is "report", "the housekeeping" is "nightly maintenance", "sharpen" is "refine", and the reading levels are Simple, Familiar and Original. Command names and flags did not change. The first line of `researchzosho jobs` now starts with `research runs:` instead of `jobs:`.

### Fixed

- The command line's help text showed the descriptions of `absorb` and `add <folder>` under `bridges`. They are back under their own commands.

## 0.4.2

This release adds three things. A `holdings` command that looks up whether a title is in one of your shelved lists, such as your Calibre library. Support for lists of any length, with `--match` and `--sample` to pick which items get research questions. And a `remove` command that deletes a report, its claims, or a single claim. It also fixes the reading of a Calibre `metadata.db` file and gives the beginner and familiar rewrites the same sections as the original report.

### Added

- `researchzosho remove <I-…|F-…>` deletes a report and its claims. `--report-only` deletes the report only. `--claims-only` deletes the claims only. You can also give one claim id. It prints the plan and asks first. A claim another report also cites is kept. Pages fetched from the web are kept. Each removal is written to the changes log. The browser has the same on each report and claim page. Over MCP and HTTP the tool is `library_remove`.
- `researchzosho holdings <words>` searches every shelved list, including a Calibre library. Every word must appear in an entry. The chat uses it to answer "do I own this". Research runs have it as a tool, so recommendations are checked against your shelf.
- `items` stores the whole list, however long. One call looks at, files or runs at most 200 items. `--match <text>` keeps items whose line contains the text. `--sample <n>` takes n of them at random.

### Fixed

- The beginner and familiar rewrites of a report keep the report's section headings, in order. They rewrite the text under each heading. They leave out the question, the conflicts, the sources, the checks and the references. Older cached rewrites are written again on the next request. The same applies on the pages and in `researchzosho explain`.
- A Calibre `metadata.db` is read as its books. This applies when you give the file on its own, to `items`, to `add`, or when a research run reads it. It used to be read as text and came back as fragments. Any other SQLite file is read as its tables and row counts.

## 0.4.1

This release adds the `survey` command. Give it a code repository, a paper, a web page or a GitHub issues page. It reads it, files one draft claim about what it is, and lists research questions you can pick from. It also lets `items` read a Calibre library folder, and shows the version number in the page header.

### Added

- `researchzosho items` takes a Calibre library folder. Each book is one item. Its authors, year, series and tags are the note. It reads `metadata.db` read-only. If the database cannot be read, it reads the `metadata.opf` files instead. In a CSV, a column named `title` is used without `--column`.
- `researchzosho survey <thing>` reads a code repository, a paper, a website or an issue tracker. A repository is a folder, or a git url that it clones with git. A paper is a PDF, a document file, a DOI or an arXiv page. An issue tracker is a GitHub issues page or an export file. It files one draft claim on what the thing is, what it claims and what it rests on. It prints numbered research directions. `--pick 1,3` runs those. `--do "…"` runs your own question. The kind is detected; `--kind` overrides it. The same works in the chat and as `library_survey` over MCP and HTTP. `researchzosho repo` is the same command for a repository.
- The pages show the version in the header next to the library's name, and in the footer. A service running from a source checkout shows its number with "(source tree)" instead of "dev".

## 0.4.0

This release adds the `bridges` command. It finds two subjects in your library that share a concept but that no source connects, and writes a research question about them. It also adds a nightly `concepts` step, a `distance` command, and the embeddings server to `model install`.

### Added

- `researchzosho bridges` looks for two areas of your catalog that could be connected but never have been. It finds pairs of subjects that share specific terms while no source on the shelves names both. It ranks the pairs and has the model write each top pair as a question. The questions go on the open questions as type `bridge`. `bridges accept <question>` starts the research run that tests one. `dismiss` drops it. `measure` shows how many proposals you kept. Settings per area: `--reach low|medium|high`, `--strict|--loose`, `--toward`, `--away`, `--since`, `--sources`, and how many proposals a night. The housekeeping runs one pass a night. In the chat, ask "what could connect X to something far from it".
- Bridges has two ways to find a pair. `--via terms` matches words that both areas' claims are about. `--via graph` walks the library's map from one area to the other through the concepts between them. So "fish oil lowers blood viscosity" and "Raynaud's involves blood viscosity" meet at "blood viscosity" even when the two sides use different words. Nodes with the same meaning, by embedding, count as one. The default is both. Each proposal says which way found it.
- With `--sources library,web`, bridges looks outside the library for a pair the shelves do not join. The map's closest concepts across the two areas are tried first and shown to the model. The model adds what else could bear on both. Each candidate is kept only when a search finds a source about it and about that area on both sides, from a journal, a reference work or the thing itself. The proposal carries the middle and both sources. Without `web` nothing leaves the library. A pair can be proposed again through a different middle on a later night. A middle searched for a pair is not searched again for a week. When several middles are backed, the one whose two sources read most alike is proposed first.
- `researchzosho bridges distance <area> <other>` prints how far apart two areas are: hops on the map, shared terms, paths, how many sources name both, and the closest concepts on each side with their cosine.
- The housekeeping has a `concepts` step. For each claim it asks the model what the claim rests on (temperature, viscosity, friction, a method). It adds those to the map as "mentions" edges. Triples say what a claim asserts. Concepts are where two distant areas can meet. `researchzosho concepts` runs the step now. `researchzosho triples` does the same for the triples step.
- `researchzosho model install` now sets up the embeddings server next to the model, at the same address. On Linux with an NVIDIA card it runs Text Embeddings Inference. On a Mac or Windows it runs llama.cpp with the Qwen3-Embedding-0.6B file. It never idles out, and the model never evicts it. `model status` shows it. Uninstall restores your old embed setting.
- If you installed the model server with an earlier release, the daemon adds the embeddings server the next time it starts. `researchzosho update now` restarts the daemon, so the update does this for you. Linux downloads nothing. A Mac or Windows fetches the 0.6 GB embedding file.

### Fixed

- The housekeeping scheduler no longer spins. An hour outside 0 to 23 was an error it retried without pausing. That wrote gigabytes to `catalog/crews.log` until the disk filled. Now an hour is folded into range (24 is midnight), any failure waits five minutes, and `--crew-hour` above 23 is refused.
- A claim the cataloguer cannot match to a subject is filed with the rest of its run, with a note saying so. It used to get no subject at all. A claim with no subject is in no area, so the map, a search scoped to a subject and bridges never saw it.
- The reviewer is given the write-up's own section headings and takes at least one claim from each. It used to take its claims from the first sections and skip the rest of the run's work.
- The embeddings server used to be a container that nothing owned. Stop it once and search went back to words only, with no message. Now llama-swap owns it and restarts it on the next request.

## 0.3.0

This release adds eight commands that start research from something you already have: a file or folder, a chat transcript, a list, a draft, a reading list, a file of questions, bookmarks, or meeting notes. The chat's tool descriptions are shorter, so smaller models can use it.

### Added

- You can point the library at a file, a folder or a URL from the chat: "read in /mnt/archive/papers". A mounted network drive counts as a folder. For a folder the Librarian counts first: files by type, size, what keeping the text would take, and free disk. Then it asks whether to keep the text or read the files in place.
- `--link` on `researchzosho add` reads files where they are instead of keeping their text. The entry holds the path, a hash and the size, nothing else. When the drive is not mounted, the entry says so. It reads again when the drive is back. A changed file is noticed by its hash at the next rescan, and the claims that rest on it are marked for review.
- `--survey` on `researchzosho add <folder>` prints the count and the sizes and shelves nothing.
- `library_add` is the same for programs. It takes `path` or `url`, `collection`, `mode` (`keep`, `link`, `survey`) and `register`. Paths are accepted from the terminal and the library's own pages only. A URL is accepted from any patron with write access. The route on the daemon is `/v1/add`.
- A linked file whose drive was away during an index rebuild is found by its title until the next rescan reads its text in.
- `researchzosho absorb <file|url>` and the `library_absorb` tool take a conversation you had with another assistant as a starting point. It reads ChatGPT and Claude exports, `[{role, content}]` lists and `User:`/`Assistant:` transcripts. The transcript is shelved in the collection `conversations`. Your questions join the open questions. The assistant's claims come back as a list to check. Nothing becomes a finding. `--verify` (or `verify: true`) files one research run that checks the claims against sources. In the chat, say "absorb this thread".
- `researchzosho items <file|url>` and the `library_items` tool take a list of things as a starting point: books, tools, or an inventory of line items. The list is one item per line, a markdown table or a CSV. The list is shelved in the collection `lists`. Each item is checked against the shelves. Each becomes a question through a lens: `--lens "{item}: …"`, or the default, which asks what it is, who made it and what it is for. `--as frontier` files the questions for the housekeeping. `--as runs` sends them out as research runs with a lane per item. `--as none` only shows what is held.
- Five more starting points, each a file you already have. `check` takes your own draft: its claims to check, its citations fetched, its questions filed. `reading` takes BibTeX, RIS, CSV or lines of DOIs and URLs, fetched onto the shelves as a collection. `questions file` puts a file of questions onto the open questions in order. `bookmarks` takes a browser export and puts the pages onto the shelves. `meeting` takes a transcript: decisions kept, questions raised filed, claims to check with who said them. Each has a `library_*` tool and a `/v1/*` route and works from the chat. A page that cannot be read becomes a source request.
- `--watch` on `reading` and `bookmarks` registers the list. The housekeeping then re-reads its pages each night and keeps a new copy when one changes.

### Changed

- The chat describes its tools to the model in a sentence or two each, instead of reusing the MCP server's paragraphs. It leaves out the patron field it fills in itself. The tool list the model reads on every turn went from about 7,500 tokens to about 3,900. More of the window is left for the conversation and the entries it opens. Programs on the MCP server still get the full descriptions.

### Fixed

- Array arguments in the tool schemas (`sources`, `sub_questions`, `ids`, `collections`) now say what their items are. gpt-oss's chat template in llama.cpp reads that field. When it was missing, every request failed with "Function is not a bool value". So the chat, and any MCP client that renders the schemas through that template, did not work on gpt-oss at all.
- The chat admits a made-up reference. A small model sometimes cites an entry id that no look-up returned. The reply now ends with "The reference … is not an entry I looked up; I should not have cited it". The turn counts it as unbacked in the ledger.

## 0.2.0

This release adds the chat. Run `researchzosho chat` or open the Chat page to ask the library questions, follow up, start research runs, and read what they found.

### Added

- `researchzosho chat` starts a conversation with the Librarian. Ask what the library holds. Ask follow-ups. Say "find out …" to start a research run. Ask later how it went. Go through the inbox. It only answers from what the library's tools return, and it tells you when the library has nothing.
- Every reply cites the entries it used, like `[F-0012-a]`, so you can open them. If the Librarian names a figure or a source that none of its look-ups returned, it says so under the reply and calls it a guess.
- After an answer it offers one related thing: a nearby finding, an open question, or a claim that disagrees.
- Conversations are saved under `catalog/chat/`. `researchzosho chat` continues the last one. `--new` starts fresh. `--sessions` lists them. `--resume <id>` reopens one. Inside a conversation: `/new`, `/sessions`, `/resume <id>`, `/help`, `/quit`.
- The same conversation is on the pages at `/chat`.
- It uses the model you already have, with thinking turned off so it answers in words. Set `RESEARCHZOSHO_JUDGE_DRIVE` to use a hosted model for the conversation while a local one does the reading.

### Fixed

- HTTP clients are shared instead of built per call. Each client owns a thread or two. A client per drive probe, per chat turn on the page and per model-server check leaked threads until a small machine ran out of them.
- The model server's context window was wrong behind llama-swap, which is what `model install` sets up. The proxy answers `/props` with "no model id" because that request does not name a model. So we assumed a window of 8192 and cleared context four times too early. We now read the window from `/upstream/<model>/props`. `RESEARCHZOSHO_CTX` still overrides it if you need to.

## 0.1.11

### Fixed

- Setup's "say hello" check works with reasoning models. It used to give the model only 12 tokens to reply. A model that thinks before it answers used them all on thinking and sent back an empty reply. Setup then told you the address was wrong. Now the check allows 400 tokens. A reply that thought but ran out of room counts as a hello. If a server really does answer with nothing, setup says the address is fine and points you at the model name instead.

## 0.1.10

### Fixed

- The nightly "enrich" step no longer runs forever. It writes a short retrieval note for every chunk of every saved page. It had no limit, so on a large library it could keep the model busy all morning. It now does at most 20 files a night (`RESEARCHZOSHO_ENRICH_PER_NIGHT`) and continues the next night. Stopping a housekeeping job with `researchzosho research stop <J-…>` now stops it at the next chunk.

## 0.1.9

This release is about the cite-check and the instruments around it. Until now the checker could only tie a citation to a reference when the writer cited a URL. On a typical write-up it read about a fifth of the citations and skipped the rest. The writer now cites by number, and the checker reads nearly everything. Every run leaves a ledger row and a trace, so the effect of a change can be measured.

### Added

- `researchzosho jobs [<J-…>]` lists every run: queued and running first, then the most recently finished. With a job id it prints that run's state, progress, any wait it is in, and the investigation its write-up went to. Since 0.1.2 the CLI has printed "researchzosho jobs J-… shows how it goes" after every `research ask`, and the guide documented the same verb. The verb did not exist until now.
- `researchzosho stats [<n>]` prints the run ledger. When a research run is filed, one row is appended to `catalog/runs.jsonl`: turns used, rounds, whether the critic was satisfied on the first pass, whether a ceiling cut the run short, the cite-check counts, sources noted, fetches (total and distinct), token usage and wall time. `stats` shows the last rows and the totals over them.
- Every run now writes a trace to `catalog/traces/<job>.jsonl`, one JSON line per model call. Each line has the exact request messages, the tools offered, the shape of the reply, the server's reported token usage and the latency. The runner's own events are there too, such as a compaction: what was fitted to the context window and how much was cut. The newest fifty traces are kept. `RESEARCHZOSHO_TRACE=off` disables it.
- Every write-up ends with a "Checks" section when there is anything to report. It lists numbers with a unit, licence names and CVE identifiers that appear in the answer but in none of the run's notes or cited sources. It lists quotations of five or more words that appear in no captured source. It lists any cited paper that Crossref lists as retracted, with the retraction notice. Nothing in the prose is rewritten. Read the section before the prose.
- `docs/MODELS.md` and researchzosho.org/models carry the measured model list by VRAM, the models that were measured and are not recommended, what a model server needs to provide, and how to point the library at a hosted API. The `models` verb now says VRAM rather than "card memory".

### Changed

- The writer is given the reference list before it starts, numbered as the references will be printed. It is asked to cite by number (`[3]`, or `[3][7]` for several) directly after the clause a source supports. The cite-check resolves bracketed numbers anywhere in a sentence. It also resolves an arXiv identifier or a DOI inside a parenthetical to the reference that carries it.
- The cite-check runs a mechanical pass before it asks the model. A clause whose numbers all appear in the cited source, or that shares an eight-word run with it, is accepted without a model call. Before a clause is marked "not supported", the judge reads the source a second time and has to quote the passage that supports the clause. The quote is checked verbatim against the source. A clause the source turns out to hold is not marked. A "not supported" verdict on a clause whose numbers and words are in the source is set aside and counted as a false negative. All of these counts appear in the ledger.
- The critic is given computed coverage figures before it reads the evidence: notes and distinct sources per sub-question, and distinct sources and hosts overall. A sub-question that produced no source at all is sent round again without asking the model. The critic's default is now "sufficient" unless a gap is specific, critical to the question and reachable with one further search. Gaps come back typed (critical, contextual, detail, extension) and marked central or not. They are researched in that order.
- If the question asks for a particular form of answer (a table, a language, a length, an ordering, a comparison), that request is separated from the question at intake and passed to the writer. The writer is also told to write to the question and nothing beside it.
- A page fetched by one reader is served to any other reader in the same run from the library's captured copy. It is not fetched again. The ledger counts these.
- Independence counting changed in three ways. First, a source that cites another is no longer treated as a copy of it. That rule was applied through union-find and so chained, and a survey that linked twenty papers turned them all into "the same text as [1]". Copies of one text still count once. A source that cites another is annotated "(cites [n]; not an independent voice for what it says)" and does not add to the count. Second, two spellings of one address are now one locator. Third, two texts with the same opening paragraphs, such as a wire story under two headlines, are one source, but only when their bodies also overlap. Pages that only share a site's navigation are not merged. The review's one-source rule and the `independent_sources` field count in the same way.
- A reference that a worker fetched but never noted or cited is annotated as such in the reference list.
- When an older tool observation is cleared from a worker's context to make room, the placeholder names the call it answered. Observations are cleared whole, not truncated.
- An arXiv identifier now stamps its posting month on notes and references (`2606.09498` is June 2026). The writer is told how to date arXiv papers from the id.
- Progress for a run with no turn ceiling says so instead of showing a ceiling of zero. It shows the time left when a minute ceiling is set.

### Fixed

- The "[cites a source that was not read this run]" mark is applied only to a URL the writer introduced itself, one that no worker noted or fetched. A noted source with no capture on disk is skipped, not marked.
- The reference list, and lines of the form `[1] url | [2] url`, are no longer treated as citing sentences.
- A parenthetical made of an acronym and a year, such as "(SIGIR 2025)", no longer maps to a reference. It names a venue, not a work.
- In the Checks section: text between two scare-quoted words is not treated as a quotation. A numeric range shares its unit across both ends and for every unit ("30–120 seconds" states both numbers). A licence name must be supported by a note that names the same product. The numbers, licence and CVE checks compare a sentence against the notes and the sources that sentence cites, not against the full text of every source in the run.

### Measured

The same five research questions were run on a local Qwen3.8-27B before and after. Cited sentences the check could read went from 52 across the five write-ups to 456. Of those 456, 338 were supported by their source (230 of them settled mechanically, 18 by the second read), 55 were not and are marked, and 63 could not be decided from the excerpt. 81 of 387 page fetches were served from a copy already in the run. Before the independence change, one write-up marked 32 of its 38 references as the same text. The worst case afterwards is 13, on a write-up whose references are mostly GitHub pages.

## 0.1.8

Fixed
- The drive probe named no model. A drive behind a router that routes by model name (llama-swap, as `model install` sets it up; Ollama) answered 404 to the probe and read as "does not answer". Research runs sat queued as "waiting for the model" forever, the nightly crews never ran, and `library_research` refused with "No model drive answers". Every real call names the model, so it would have worked. The probe now names the configured model. When none is set it uses `local-model`, the alias the install writes. Seen on a real node on 2026-09-12. The fix means 0.1.7's own model install is usable by its own runs.
- A drive that accepts the connection but has not finished loading its model now reads as starting on the boot line, not as absent. That covers a 503, or a body that outlasts the probe's 20 seconds on a cold start.
- The MCP server introduced itself as 0.1.2 whatever the release. It now says the release's version.
- `researchzosho model check` (which the release build runs) read a rate-limited host (HTTP 429) as a missing file. It now asks again, twice, twenty seconds apart. Then it reports the row as not checked rather than gone.

## 0.1.7

Added
- `researchzosho model install` serves the model on this machine, on demand, on Linux, macOS and Windows. It picks the measured model for the machine's memory and downloads it once. It puts llama.cpp behind a small proxy (llama-swap) as a service that starts with your session on port 8211, and sets the drive to it. The model comes up when a run needs it and goes away after 20 idle minutes, so the memory is free in between and nothing has to be up all the time. On Linux the server is llama.cpp's CUDA container (Docker with the NVIDIA runtime). On macOS it is the Metal build, sized by unified memory, as a launchd agent. On Windows it is the Vulkan build, which runs on any card, as a logon task. Every download is checked against a recorded sha256 (the model files as Hugging Face lists them, the proxy's published checksums, the pinned llama.cpp build) and refused on a mismatch. `researchzosho model check` reads every row and pinned build, and the release build runs it. Uninstall refuses while the other product's settings still point at the proxy (`--force` overrides). Setup offers it when it finds no model server. `model status`, `model stop` and `model uninstall` go with it. `--share` lets other machines use this one. A proxy already on 8211 is used without change.
- A run whose model server is not answering says so: "waiting for the model" on its record, in `library_job`, and on the Runs page, with the address and the retry cadence. A model server that sleeps between uses (llama-swap, Ollama) reads as starting, not stuck.
- The guide has a section on letting the model server sleep. The library never needed it up all the time. A proxy on the GPU machine that starts the server on demand and stops it when idle keeps the card free in between. Every program points at the proxy, so moving the model to another machine is one address change.

## 0.1.6

Fixed
- On a long run the write-up's author saw only the first sub-investigations' notes. The evidence was cut from the tail to fit the model's context. On a five-lane run through a 32k slot, the fourth and fifth reports never reached the writer, and the answer called those lanes untested while they held 17 sources. Now every report keeps its head and shares the room. The writer sees a coverage line per sub-investigation first. A sentence that claims nothing was found on a lane that noted three or more sources is listed in a "Coverage check" section of the write-up and on the log.
- A second round of research got no reading time. The first round could run to the deadline's edge, and the critic's questions were then searched once and never fetched. With a deadline, the first round now stops at three fifths of the time. A second round is refused, and its questions recorded as open, when fewer than four minutes a worker remain. Each second-round worker starts by fetching the pages the first round named and could not read.
- The cite-check read a whole sentence against the source its last citation named. A sentence of the shape "(a) … (source); (b) no evidence was found …; (c) …" was marked unsupported for claims it never attributed to that source. It now reads the clause a citation closes, and marks that clause after its citation. Sentence boundaries no longer break on the dots inside a cited URL or after "et al.".
- Setup minted a new identity for a program every time it ran. A library set up three times listed three "(Claude Code)" writers for one person. The identity is now one per machine, person and program, and setup rewrites the same line.
- A PDF the built-in reader garbled is read with poppler's `pdftotext` when the machine has it (`apt install poppler-utils`, `brew install poppler`). The built-in reader stays the fallback. `RESEARCHZOSHO_PDFTOTEXT=off` keeps to the built-in one.

Added
- `library_get` takes `section`, `offset` and `max_chars`. `section` is a heading, or `answer` for the write-up without the harness's own sections, or `workers`, `references` or `evidence`. Every entry reports `chars`. So a program can read a 150,000-character investigation a section at a time instead of as one blob. An investigation now also carries `sections[]` (heading and size), `sources[]` as rows (number, locator, title or edition, published date, whether its text is on the shelves, the language of the notes taken from it, which reference it duplicates), `claims[]` (its findings with their bodies) and `open_questions[]`.
- A running job's record and the Runs page carry `progress`: the phase (planning, workers, critic, synthesis, cite-check, filing), the round, workers finished of the round, and turns used of the ceiling. A client knows when to poll again.
- `npx -y @wyrdsekai/researchzosho-mcp` starts the MCP server from any client that runs npm packages. The launcher finds an installed ResearchZosho, or fetches the release of the same version, checks it against the release's checksums and unpacks it under `~/.researchzosho/launcher`. `researchzosho mcp` on a machine with no library makes one instead of refusing. The library is listed in the MCP Registry as `io.github.Wyrdsekai/researchzosho`.
- Builds with their own Java runtime, one per platform (Linux and macOS on x64 and arm64, Windows x64): `researchzosho-<version>-<platform>.tar.gz`, about 60 MB, nothing to install first. The install one-liners take one when the machine has no Java 21 (`RESEARCHZOSHO_RUNTIME=1` asks for it). The npm launcher does the same. `update now` on such an install stays on its own kind.
- A container image, `ghcr.io/wyrdsekai/researchzosho:<version>`, with the library and the settings on volumes and the pages on 4649. `docker-compose.yml` runs it beside an embedder. Its `mcp` argument is the same server over stdio.
- `researchzosho models` prints the measured model choice for this machine's card (24 GB and up, 16, 8, 4, 2), with the command that serves it. `--all` prints every row. Setup prints the row for the card when it finds no model server.

Changed
- The pages say what they are: ResearchZosho in the header and the title, then the library's name.

Note
- A library set up before 0.1.5 has `default: read` in `catalog/patrons.md`. The setup of the day wrote it; nobody chose it. A program without a token could read but not file runs. `researchzosho reader default write` opens it, as a fresh library is.

## 0.1.5

Changed
- Programs are open as shipped, like the pages. A caller not on the access list can read, ask, file runs and submit claims. Before, an unlisted program could only read, so a Claude Code registered by hand was refused when it filed a run. `researchzosho reader default read` or `deny` restricts. `reader allow <did> write <name>` lets a named program through.

Added
- The library has a name of its own. Setup asks for it. `researchzosho name <a name>` changes it. It is the `name:` line of `catalog/library.md`. The pages and `library_name` show it. Until set, the folder's name is used, as before.

Fixed
- The Runs page and the home page said "Nothing is running" while a run filed by a program (Claude Code, the chat) was going. The browser saw only runs filed from the browser. It sees every run now.
- With `web signin on`, a browser that had not signed in could still send questions when the default level allowed writing. It now reads at most until it signs in.

## 0.1.4

Fixed
- `researchzosho update now` failed with "HTTP 302". A GitHub release asset is served through a redirect, and the downloader did not follow it. The update check was unaffected. On 0.1.2 and 0.1.3, update with the install one-liner instead.

## 0.1.3

Fixed
- A worker's "Sources:" line followed by prose no longer turns every later sentence with a year into a reference row. 36 of one write-up's 90 references were junk.
- The claim extraction fits the record to the drive's context window with room for the reply. On an 8k-token slot the prompt used to fill all but a few dozen tokens, and the extraction came back empty.
- A claim extraction cut off mid-reply keeps its whole candidates instead of yielding nothing. When the reply cannot be read at all, the review logs the reason and the reply's first words (`review` lines in `catalog/crews.log`; the settle line counts them).

## 0.1.2

Added
- Web: an Inbox page. Select claims with checkboxes, then Accept, Retire, or Dispute (with a reason).
- Web: the Open questions page. Select questions with checkboxes, then send them as research runs or drop them. Change a kept search's interval in place.
- CLI: `researchzosho tonight` prints what the next housekeeping run will do. The Runs page shows the same.
- CLI: `shelf remove <name>`, `shelf every <name> <days>`, `questions list|add|drop`.
- MCP/HTTP: `library_serials` (list, add, every, remove). `library_frontier` gets `op=drop`.
- Vault: a `Housekeeping.md` note listing kept searches and open questions, with the paths of the two source files.
- `library_status` returns `version`. `researchzosho status` and the page footer report when a newer release exists.
- Open questions are a queue with types (report, asked, person, dispute, check), order, and a parked state. CLI: `questions next|later|park|unpark`, `questions budget <n>`, `questions tonight <n>`. Web: filter by type and by queued or parked; Run next, Later, Park, Drop on selected. MCP/HTTP: `library_frontier` ops next, later, park, unpark; list returns type, parked, position, tonight.
- The explorer bundles related open questions (same report, or overlapping terms) into one run, up to 8 per run. The per-night count is read at run time. `explorer.tonight` overrides it for one night. `RESEARCHZOSHO_EXPLORER_TYPES` selects the types it takes; `check` is never taken. `RESEARCHZOSHO_EXPLORER_MINUTES` caps a run.
- `researchzosho update [now | auto on|off]` installs the latest release in place (checksum-verified) and restarts the service. `RESEARCHZOSHO_UPDATE=auto` lets the service do it after the housekeeping when idle.
- Open questions can be filtered eight ways on the page, the CLI (`questions list --report|--fate|--who|--subject|--language|--grep|--by-report|--hints`), and `library_frontier` (`report`, `fate`, `who`, `subject`, `language`, `q`, `show`, `hints`). The filters are: the report that left them (grouped, one checkbox per group), what became of that report, the perspective asked from, subject, language, words, questions that read alike (folded), and claims that may already answer them. The list returns `report`, `report_title`, `report_fate`, `perspective`, `subjects`, `language`, `similar`, `answered`.
- `researchzosho questions tidy` and `library_frontier op=tidy` remove duplicate question lines. The page offers the same when it finds any.
- `researchzosho embed start|stop|status|test` runs an embeddings server through Docker (Text Embeddings Inference, Qwen3-Embedding-0.6B). It uses the GPU image for the machine's NVIDIA card. `--cpu` uses the CPU image, which is slow. With it, search works by meaning. Setup offers it when Docker and an NVIDIA card are present and the model server does not embed.
- `researchzosho refresh` re-indexes what changed or was removed on disk in seconds. `rebuild` says that it re-embeds everything. The nightly refresh now drops entries whose files were removed.
- The inbox can be filtered nine ways on the page, the CLI (`inbox --report|--subject|--kind|--tier|--confidence|--writer|--state|--language|--grep|--by-report`), and over MCP/HTTP. The filters are: the report the claim came from (grouped, one checkbox per group), why it waits, kind, source tier, confidence, writer, subject, language, words.
- MCP/HTTP: `library_inbox` (list with the filters; accept, dispute with `why`, retire by `ids`, `id`, or `report`). Before this, a program could not accept, dispute, or retire a claim.
- CLI: `accept --report I-…` and `retire --report I-…` decide every waiting claim of one report.
- The Open questions page links each report's group to its claims in the Inbox. The Inbox links each report's group to its open questions.
- A kept search can be parked: it is kept and shown, but never run until unparked. "Park" and "Back in the rotation" on the page, `shelf park|unpark <name>`, `library_serials` ops park and unpark, `| parked` on the line in `catalog/shelves.md`.
- Runs page: "Pause the runner" and "Resume" (the pause the command line already had), and "Stop" beside each queued or running run. `researchzosho research stop <J-…>`; `library_job` ops stop, pause, resume. A stopped run's state is `stopped`, not `failed`. A running run ends at its next turn. A stop during the after-steps (review, subjects, triples, retractions, summaries) ends them at the next step, and the nightly housekeeping does the rest.

Changed
- The questions a report leaves open are filed parked. Nothing runs on them until a person unparks them. `RESEARCHZOSHO_REPORT_QUESTIONS=queued` restores the old behaviour.

Fixed
- `researchzosho service install` finds the launcher beside its own program. It works from a shell whose PATH does not have it yet (a fresh macOS install).
- A report's open questions were filed twice (once by the run, once by the review), so the list showed each of them two times.
- Open questions that had been explored or dropped were still listed as open (on the Open questions page and in `library_ask` open threads).
- Search result lines use `:` instead of `—`. The Windows console could not print the dash.

## 0.1.1

Added
- Setup asks for a web search backend: a Brave Search API key first, then SearXNG (existing, or started with Docker), else the built-in fallback.
- CLI: `researchzosho search status|start|stop|test|papers`. `search start` runs SearXNG in Docker with a settings file that enables JSON output and a tested engine set.
- Built-in fallback search: Wikipedia plus Crossref and OpenAlex. No key or install needed. `RESEARCHZOSHO_FALLBACK_SEARCH=off` disables it.
- `scholar_search` tool in every research run (Crossref and OpenAlex, results by DOI).
- Reports get a "Web search" section when the fallback was used or no backend answered.

Changed
- Guide renamed from `LIBRARIAN_DRIVING.md` to `LIBRARIAN_HOWTOUSE.md`.

## 0.1.0

First release.

- Research runs: question in, report out, with an evidence table and a source list. Multi-language sources.
- Source checks: cited sentences checked against sources; single-source claims stay drafts; retracted papers flagged; ban or trust sites.
- Review: accept, dispute, or retire claims. `researchzosho ask` shows what the library holds.
- Add your own documents (PDF, web page, folder).
- Map of people, places, works, and subjects.
- Explain: rewrite an entry at beginner, familiar, or as-written level from library content only.
- Web pages: search, ask, read, map, send a question, download reports as Markdown or PDF. Sign-in off by default.
- Vault export for Obsidian or SoloMD.
- Nightly housekeeping: kept searches, open questions, re-checks, preprint and retraction checks, backup.
- MCP (stdio and HTTP), HTTP API, Python client (PyPI `researchzosho`), Java client (Maven `org.researchzosho:client`). Setup connects Claude Code, Codex, and Gemini CLI.
