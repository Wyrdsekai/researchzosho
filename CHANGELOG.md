# Changelog

## 0.1.8

Fixed
- The drive probe named no model, so a drive behind a router that routes by model name (llama-swap, as `model install` sets it up; Ollama) answered 404 to the probe and read as "does not answer": research runs sat queued as "waiting for the model" forever, the nightly crews never ran, and `library_research` refused with "No model drive answers", while every real call, which does name the model, would have worked. The probe now names the configured model (`local-model`, the alias the install writes, when none is set). Seen on a real node on 2026-09-12: the fix means 0.1.7's own model install is usable by its own runs.
- A drive that accepts the connection but has not finished loading its model (a 503, or a body that outlasts the probe's 20 seconds on a cold start) now reads as starting on the boot line, not as absent.
- The MCP server introduced itself as 0.1.2 whatever the release; it now says the release's version.
- `researchzosho model check` (which the release build runs) read a rate-limited host (HTTP 429) as a missing file; it now asks again, twice, twenty seconds apart, and then reports the row as not checked rather than gone.

## 0.1.7

Added
- `researchzosho model install`: the model on this machine, on demand, on Linux, macOS and Windows. It picks the measured model for the machine's memory, downloads it once, puts llama.cpp behind a small proxy (llama-swap) as a service that starts with your session on port 8211, and sets the drive to it: the model comes up when a run needs it and goes away after 20 idle minutes, so the memory is free in between and nothing has to be up all the time. On Linux the server is llama.cpp's CUDA container (Docker with the NVIDIA runtime); on macOS its Metal build, sized by unified memory, as a launchd agent; on Windows its Vulkan build, which runs on any card, as a logon task. Every download is checked against a recorded sha256 (the model files as Hugging Face lists them, the proxy's published checksums, the pinned llama.cpp build) and refused on a mismatch; `researchzosho model check` reads every row and pinned build, and the release build runs it. Uninstall refuses while the other product's settings still point at the proxy (`--force` overrides). Setup offers it when it finds no model server. `model status`, `model stop` and `model uninstall` beside it; `--share` lets other machines use this one, and a proxy already on 8211 is used as it is.
- A run whose model server is not answering says so: "waiting for the model" on its record, in `library_job`, and on the Runs page, with the address and the retry cadence. A model server that sleeps between uses (llama-swap, Ollama) reads as starting, not stuck.
- The guide has a section on letting the model server sleep: the library never needed it up all the time, and a proxy on the GPU machine that starts the server on demand and stops it when idle keeps the card free in between. Every program points at the proxy, so moving the model to another machine is one address.

## 0.1.6

Fixed
- The write-up's author saw only the first sub-investigations' notes on a long run: the evidence was cut from the tail to fit the model's context, so on a five-lane run through a 32k slot the fourth and fifth reports never reached the writer and the answer called those lanes untested while they held 17 sources. Now every report keeps its head and shares the room, the writer sees a coverage line per sub-investigation first, and a sentence that claims nothing was found on a lane that noted three or more sources is listed in a "Coverage check" section of the write-up and on the log.
- A second round of research got no reading time: the first round could run to the deadline's edge, and the critic's questions were then searched once and never fetched. With a deadline, the first round now stops at three fifths of the time; a second round is refused, and its questions recorded as open, when fewer than four minutes a worker remain; and each second-round worker starts by fetching the pages the first round named and could not read.
- The cite-check read a whole sentence against the source its last citation named, so a sentence of the shape "(a) … (source); (b) no evidence was found …; (c) …" was marked unsupported for claims it never attributed to that source. It now reads the clause a citation closes, and marks that clause after its citation. Sentence boundaries no longer break on the dots inside a cited URL or after "et al.".
- Setup minted a new identity for a program every time it ran, so a library set up three times listed three "(Claude Code)" writers for one person. The identity is now one per machine, person and program, and setup rewrites the same line.
- A PDF the built-in reader garbled is read with poppler's `pdftotext` when the machine has it (`apt install poppler-utils`, `brew install poppler`); the built-in reader stays the fallback. `RESEARCHZOSHO_PDFTOTEXT=off` keeps to the built-in one.

Added
- `library_get` takes `section` (a heading, or `answer` for the write-up without the harness's own sections, `workers`, `references`, `evidence`), `offset` and `max_chars`, and every entry reports `chars`, so a program can read a 150,000-character investigation a section at a time instead of digesting one blob. An investigation now also carries `sections[]` (heading and size), `sources[]` as rows (number, locator, title or edition, published date, whether its text is on the shelves, the language of the notes taken from it, which reference it duplicates), `claims[]` (its findings with their bodies) and `open_questions[]`.
- A running job's record and the Runs page carry `progress`: the phase (planning, workers, critic, synthesis, cite-check, filing), the round, workers finished of the round, and turns used of the ceiling — a client knows when to poll again.
- `npx -y @wyrdsekai/researchzosho-mcp` starts the MCP server from any client that runs npm packages: the launcher finds an installed ResearchZosho, or fetches the release of the same version, checks it against the release's checksums and unpacks it under `~/.researchzosho/launcher`. `researchzosho mcp` on a machine with no library makes one instead of refusing. The library is listed in the MCP Registry as `io.github.Wyrdsekai/researchzosho`.
- Builds with their own Java runtime, one per platform (Linux and macOS on x64 and arm64, Windows x64): `researchzosho-<version>-<platform>.tar.gz`, about 60 MB, nothing to install first. The install one-liners take one when the machine has no Java 21 (`RESEARCHZOSHO_RUNTIME=1` asks for it), the npm launcher does the same, and `update now` on such an install stays on its own kind.
- A container image, `ghcr.io/wyrdsekai/researchzosho:<version>`, with the library and the settings on volumes and the pages on 4649; `docker-compose.yml` runs it beside an embedder. Its `mcp` argument is the same server over stdio.
- `researchzosho models`: the measured model choice for this machine's card (24 GB and up, 16, 8, 4, 2), with the command that serves it; `--all` prints every row. Setup prints the row for the card when it finds no model server.

Changed
- The pages say what they are: ResearchZosho in the header and the title, then the library's name.

Note
- A library set up before 0.1.5 has `default: read` in `catalog/patrons.md`, written by the setup of the day rather than chosen: a program without a token could read but not file runs. `researchzosho reader default write` opens it, as a fresh library is.

## 0.1.5

Changed
- Programs are open as shipped, like the pages: a caller not on the access list can read, ask, file runs and submit claims. Before, an unlisted program could only read, so a Claude Code registered by hand was refused when it filed a run. `researchzosho reader default read` or `deny` restricts; `reader allow <did> write <name>` lets a named program through.

Added
- The library has a name of its own: setup asks for it, `researchzosho name <a name>` changes it, and it is the `name:` line of `catalog/library.md`. The pages and `library_name` show it. Until set, the folder's name is used, as before.

Fixed
- The Runs page and the home page said "Nothing is running" while a run filed by a program (Claude Code, the chat) was going: the browser saw only runs filed from the browser. It sees every run now.
- With `web signin on`, a browser that had not signed in could still send questions when the default level allowed writing. It now reads at most until it signs in.

## 0.1.4

Fixed
- `researchzosho update now` failed with "HTTP 302": a GitHub release asset is served through a redirect and the downloader did not follow it. The update check was unaffected. On 0.1.2 and 0.1.3, update with the install one-liner instead.

## 0.1.3

Fixed
- A worker's "Sources:" line followed by prose no longer turns every later sentence with a year into a reference row (36 of one write-up's 90 references were junk).
- The claim extraction fits the record to the drive's context window with room for the reply; on an 8k-token slot the prompt used to fill all but a few dozen tokens and the extraction came back empty.
- A claim extraction cut off mid-reply keeps its whole candidates instead of yielding nothing; when the reply cannot be read at all, the review logs the reason and the reply's first words (`review` lines in `catalog/crews.log`; the settle line counts them).

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
- The explorer bundles related open questions (same report, or overlapping terms) into one run, up to 8 per run. The per-night count is read at run time; `explorer.tonight` overrides it for one night. `RESEARCHZOSHO_EXPLORER_TYPES` selects the types it takes; `check` is never taken. `RESEARCHZOSHO_EXPLORER_MINUTES` caps a run.
- `researchzosho update [now | auto on|off]`: install the latest release in place (checksum-verified) and restart the service. `RESEARCHZOSHO_UPDATE=auto` lets the service do it after the housekeeping when idle.
- Open questions can be filtered eight ways on the page, the CLI (`questions list --report|--fate|--who|--subject|--language|--grep|--by-report|--hints`), and `library_frontier` (`report`, `fate`, `who`, `subject`, `language`, `q`, `show`, `hints`): by the report that left them (grouped, one checkbox per group), what became of that report, the perspective asked from, subject, language, words, questions that read alike (folded), and claims that may already answer them. The list returns `report`, `report_title`, `report_fate`, `perspective`, `subjects`, `language`, `similar`, `answered`.
- `researchzosho questions tidy` and `library_frontier op=tidy` remove duplicate question lines. The page offers the same when it finds any.
- `researchzosho embed start|stop|status|test`: an embeddings server through Docker (Text Embeddings Inference, Qwen3-Embedding-0.6B; the GPU image for the machine's NVIDIA card; `--cpu` for the CPU image, which is slow), so search works by meaning. Setup offers it when Docker and an NVIDIA card are present and the model server does not embed.
- `researchzosho refresh` re-indexes what changed or was removed on disk in seconds; `rebuild` says that it re-embeds everything. The nightly refresh now drops entries whose files were removed.
- The inbox can be filtered nine ways on the page, the CLI (`inbox --report|--subject|--kind|--tier|--confidence|--writer|--state|--language|--grep|--by-report`), and over MCP/HTTP: by the report the claim came from (grouped, one checkbox per group), why it waits, kind, source tier, confidence, writer, subject, language, words.
- MCP/HTTP: `library_inbox` (list with the filters; accept, dispute with `why`, retire by `ids`, `id`, or `report`). Before this, a program could not accept, dispute, or retire a claim.
- CLI: `accept --report I-…` and `retire --report I-…` decide every waiting claim of one report.
- The Open questions page links each report's group to its claims in the Inbox; the Inbox links each report's group to its open questions.
- A kept search can be parked: kept and shown, never run until unparked. "Park" and "Back in the rotation" on the page, `shelf park|unpark <name>`, `library_serials` ops park and unpark, `| parked` on the line in `catalog/shelves.md`.
- Runs page: "Pause the runner" and "Resume" (the pause the command line already had), and "Stop" beside each queued or running run. `researchzosho research stop <J-…>`; `library_job` ops stop, pause, resume; a stopped run's state is `stopped`, not `failed`. A running run ends at its next turn; a stop during the after-steps (review, subjects, triples, retractions, summaries) ends them at the next step and the nightly housekeeping does the rest.

Changed
- The questions a report leaves open are filed parked. Nothing runs on them until a person unparks them. `RESEARCHZOSHO_REPORT_QUESTIONS=queued` restores the old behaviour.

Fixed
- `researchzosho service install` finds the launcher beside its own program, so it works from a shell whose PATH does not have it yet (a fresh macOS install).
- A report's open questions were filed twice (once by the run, once by the review), so the list showed every one of them two times.
- Open questions that had been explored or dropped were still listed as open (on the Open questions page and in `library_ask` open threads).
- Search result lines use `:` instead of `—` (the Windows console could not print the dash).

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
