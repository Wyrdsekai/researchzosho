# Changelog

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
