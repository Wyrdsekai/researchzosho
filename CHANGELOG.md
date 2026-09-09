# Changelog

Notable changes. While at 0.x, minor versions may change behaviour.

## 0.1.1 — web search, set up properly

- Setup asks which web search backend to use, in this order: a Brave Search API key; SearXNG, either
  one already running or one setup starts with Docker; with neither, the built-in fallback. Research
  runs need a search backend as much as a model, and the docs say so.
- `researchzosho search` shows which backend answers. `search start` runs SearXNG with Docker, with a
  settings file that turns on the JSON format and an engine set measured to answer from a home
  machine. `search test <query>` runs one search and says which backend answered. `search papers
  <query>` searches the literature.
- The built-in fallback: Wikipedia's search in the query's language, then papers from Crossref and
  OpenAlex. No key, no install. `RESEARCHZOSHO_FALLBACK_SEARCH=off` turns it off.
- `scholar_search` in every research run: papers, books and chapters by DOI from Crossref and OpenAlex.
- The report's "Web search" section says when a run went through the fallback, or when no backend
  answered.
- The guide is `LIBRARIAN_HOWTOUSE.md` (was `LIBRARIAN_DRIVING.md`).

## 0.1.0 — first release

- Research runs: a question in, a report out, with a table of the facts used and a list of sources.
  Sources in other languages when the question calls for it.
- Source checks: each cited sentence checked against its source; a claim with one source stays a
  draft until a second, independent source backs it; retracted papers flagged; sites you ban or trust.
- Review: accept, dispute or retire each claim. `researchzosho ask` shows what the library has.
- Your own documents: a PDF, a web page or a folder, shelved as a collection.
- The map of people, places, works and subjects.
- Explain: an entry rewritten at a beginner, familiar or as-written level, from the library only.
- Web pages: search, ask, read, map, send a question, download a report as Markdown or PDF. Sign-in
  off by default; `researchzosho web signin on` turns it on.
- Vault: a folder Obsidian or SoloMD can open.
- Nightly housekeeping: ongoing searches, open questions, re-checks of accepted claims, new versions
  of cited papers, a backup.
- For programs: MCP over stdio and HTTP, an HTTP API, a Python client (`researchzosho` on PyPI) and a
  Java client (`org.researchzosho:client` on Maven Central). Setup connects Claude Code, Codex and
  Gemini CLI.
