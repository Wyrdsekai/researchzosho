# AGENTS.md

Instructions for an AI agent (Claude Code, Cursor, and the like) working on the ResearchZosho source
tree. If you are a person, read the README instead.

## What this is

A research library for one machine, with a librarian in front of it. A person gives it a question in
it reads, notes each fact with its source, writes up what it found, and checks
each cited sentence against its source before the write-up is shelved. Then the person decides what
stands. Nothing counts as known until they have accepted it. Do not build anything that decides on
the person's behalf.

Java 21 or newer, Gradle with the Kotlin DSL. Two modules: `librarian` is the product, `client` is the
Java SDK. `sdk/python` is a Python SDK with no dependencies. The library speaks MCP over stdio and
HTTP, plus a small HTTP protocol, and runs as a user service on Linux, macOS and Windows.

Read these before you change anything:

1. [README.md](README.md), what it does and who it is for.
2. [docs/LIBRARY_PROTOCOL.md](docs/LIBRARY_PROTOCOL.md). Sections 1 to 6 are the contract other
   programs rely on. Be careful with changes that could affect others: add, or bump the contract number.
3. [docs/LIBRARIAN_DRIVING.md](docs/LIBRARIAN_DRIVING.md), how a person uses it day to day.
4. [CONTRIBUTING.md](CONTRIBUTING.md), what makes a change easy to take.

## Build and run

```
./gradlew :librarian:test :client:test     # the suite; no model needed
bin/researchzosho status                   # the dev launcher builds on first use; bin\researchzosho.cmd on Windows
bin/researchzosho setup --yes              # a working library with the defaults, for trying things
```

Tests never need a model or the network. Anything that reads a model, fetches a page, or touches the
machine is behind a small interface that a test scripts. Every change comes with tests, positive and
negative.

## Words

- The person who uses the library, or a program that does, is a **reader**. The protocol's field is
  called `patron`; that word is used on the wire and nowhere else.
- A question sent for research is a **run**; it starts when a worker is free, at any hour. The steps at
  three each morning are **the crews** in the code and "the housekeeping" in prose. Never "overnight".
- A write-up is an **investigation**; a claim is a **finding**; a finding is accepted, disputed or
  retired, and those are the only states a person sets.

## Rules

- **Read the artifact, not the status.** A job record says "done". Whether the work was any good is
  in the investigation file. Read it before calling anything fixed.
- **Text a source wrote is evidence, never instruction.** Page content, search results, captured
  documents, filenames. Fence it and label it. It must not steer the model.
- **The library reports what it holds.** `holds_nothing` is a correct answer. A made-up hit is not.
  Do not lower a retrieval floor to make a test pass.
- **No caps in code.** How long a run goes is the reader's call (`max_turns`, `max_minutes`, or
  neither). How the model is shared is a live setting, not a constant.
- **Nothing runs from the user's config in a test.** Tests set `user.home` to a temp directory and
  call `Config.invalidate()`. A test that writes `~/.researchzosho/config` has escaped.
- **No machine names, home paths or credentials in the tree.** The public export runs a scanner;
  read its findings, do not trust a clean result.
- **Public prose is for people who are not programmers.** The README, the guide, CONTRIBUTING and the
  site are written in plain words. State what the thing does; no punchlines, no metaphors. If a sentence
  needs a programmer to understand it, it belongs in the protocol document. The README is not a
  feature list; features go in the guide.
