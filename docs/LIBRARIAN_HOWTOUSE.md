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
9. Languages
10. Checks against bad sources
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
23. Not yet available

## 1. What you need

- Java 21 or newer, or none: each release also carries builds with their own Java runtime for Linux,
  macOS and Windows (x64 and arm64). The install one-liners pick one when the machine has no Java 21.
  `RESEARCHZOSHO_RUNTIME=1` asks for it outright.
- A model server that speaks the OpenAI chat API. This can be a local server (llama.cpp, Ollama, LM
  Studio) or a hosted API with a key (OpenAI, DeepSeek, Gemini, OpenRouter and any other that speaks
  the OpenAI chat API). Setup asks for the address and, for a hosted API, the key. Without a model
  you can still ask what the library has and read it. Research runs need the model.
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
  with an NVIDIA card it is Text Embeddings Inference serving Qwen3-Embedding-0.6B. On a Mac or
  Windows it is llama.cpp's own build. The model downloads once, about 1.2 GB. Without a GPU on Linux
  there is none. `researchzosho embed start --cpu` runs the CPU image at about a tenth of a chunk a
  second, so a thousand documents take more than a day. An embeddings server on another machine is
  the better answer. Any server that answers the OpenAI embeddings call works. The server matters more
  than the model: the same 0.6B model does 13 chunks a second under llama.cpp and 115 under Text
  Embeddings Inference on the same card. A library of a thousand documents re-indexes in two minutes
  instead of twenty.

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
   The one `model install` sets up does. Otherwise, with Docker and an NVIDIA card, setup offers to
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

1. **The Brave Search API.** The best results. Get a key from https://brave.com/search/api/ (there
   is a free plan). Put it in `RESEARCHZOSHO_BRAVE_KEY`, or give it to setup. The key is sent only
   to Brave.
2. **SearXNG**, a search engine you run yourself. Free, and your queries stay on your machine.
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
3. **The built-in fallback**, when neither is set. It uses Wikipedia's own search, in the language of
   the query, then papers from Crossref and OpenAlex. No key and no install, and all three answer
   reliably. But it finds encyclopedia pages and the literature, not the whole web. A run follows
   the pages' references for primary sources. `RESEARCHZOSHO_FALLBACK_SEARCH=off` turns it off.

With a key and a SearXNG both set, Brave is used and SearXNG is the fallback. The report's "Web
search" section says when a run went through the fallback, or when no backend answered.

Every research run also has `scholar_search`, separate from all three: Crossref and OpenAlex,
papers and books by DOI. It is in every configuration. A web engine ranks the primary literature
low or not at all, and a DOI is a source the library can resolve and check.

```
researchzosho search                 # which backend answers, and the container's state
researchzosho search test <query>    # one search, and which backend answered it
researchzosho search papers <query>  # the literature by DOI
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
researchzosho update            # the installed release, the latest, and the mode
researchzosho update now        # download, verify against the release's checksums, swap in, restart the service
researchzosho update auto on    # the service updates itself after each housekeeping, when no run is active
```

The setting is `RESEARCHZOSHO_UPDATE`: `check` (default), `auto`, or `off`. An update never touches
the library or the settings. On Windows the new version goes in place when `update now` has ended,
and a service that was running starts again on it. Open a new terminal and run
`researchzosho --version` to see it. What the update did is written to
`~/.researchzosho/logs/update.log`.

One update runs at a time. An update that starts while another is running, from this program or
another one, changes nothing and says so. On Windows that holds until the new version is in place.

#### For a program that keeps ResearchZosho up to date

CodeZaiku, Wyrdsekai and any other program that updates ResearchZosho ask its own updater. They
never download it or replace its files themselves:

```
researchzosho update --json        # installed, running, latest, newer, mode, root, canUpdate, updating
researchzosho update now --json    # result, code, from, to, finishesAfterExit, note
```

`update now` ends with 0 when it updated or the install was already current, 75 when another update
is running (ask again later), 3 when this install cannot update itself (a run from the source tree),
and 1 when it failed. With `--json` it prints one JSON document, and the progress goes to the error
stream. `installed` is the version whose files are in place, and `running` the one answering, which
differ until a program started before the update is started again. Only ResearchZosho's own service
updates it without being asked, and only with `RESEARCHZOSHO_UPDATE=auto`. While the service
restarts after an update, calls to it fail for a few seconds: try them again.

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
researchzosho research ask "…" --shelves        # read only your own documents
researchzosho research ask "…" --max-turns 200  # limit the run to 200 model steps
researchzosho research ask "…" --max-minutes 120
researchzosho research ask "…" --genealogy      # research it in genealogy mode: records, your family tree, your relatives
researchzosho research ask "…" --youtube        # the YouTube field: channels, videos, what is said and when (needs `researchzosho video install`)
```

From Claude Code or a program, the same thing is `library_research`. From the web pages, use the
Research page.

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
(`researchzosho video install`). [YOUTUBE.md](YOUTUBE.md) has the setup, the five commands for looking
things up yourself, and the rules a run keeps in that field.

The run starts as soon as a worker is free. `researchzosho jobs` lists the runs, queued and running
ones first. `researchzosho jobs <J-id>` shows one run: its state, how far it has got, what it is
waiting for, and where its write-up went. A run that has shown no progress for a quarter of an hour
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
   settled.
5. Checks. The library adds a table of every fact with its source and quote. It adds a numbered list
   of sources, in which copies of one text count as one. Each sentence that cites a source is then
   compared with that source. Sentences the source does not support are marked.

The write-up is saved as a draft, with the readers' notes attached.

### Limits

With no limit, a run continues until the work is done. You can limit it:

- `--max-turns N`: at most N model steps for the whole run.
- `--max-minutes N`: at most N minutes. The readers stop early enough for the write-up to be
  finished. The write-up itself is never cut short, so the run may go a few minutes over.

With a limit in steps, the library plans only what fits. The rest goes on the open-questions list.

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
  (a server that refuses a temperature) or `RESEARCHZOSHO_DRIVE_TEMPLATE_KWARGS` (a model that
  thinks before it answers) usually mends it. A check asked while you wait, when you add a page or
  send a question, takes at most thirty seconds.
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

On the web pages, every entry has a Download line: Markdown, or a PDF with page numbers and the
sources at the end. From the command line:

```
researchzosho export I-0009 --pdf
researchzosho export I-0009 --md --beginner
```

## 6. Reviewing the claims

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

### From the chat

You can do the same in the chat with the Librarian:

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
terminal or the library's own pages. Any patron with write access can add a URL.

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

## 9. Languages

A model left alone searches in English. When a question names a place or a culture whose language
differs from the question's language, the plan gets one extra sub-question: what sources in that
language say. The reader on that sub-question:

- starts from search queries in that language;
- is told when a search it wrote is in the wrong language;
- tries again if the first round found nothing in that language.

The write-up ends with a line that lists the languages of its sources. There are at most two extra
languages per question. The language of the question itself is never added.

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
researchzosho research workers 2              # how many readers work at once (default 3)
researchzosho research pause                  # hold everything at the next step
researchzosho research resume
researchzosho research stop J-0031             # end one run within seconds; a queued one never starts
researchzosho research window 22:00-07:00     # only start runs in these hours
researchzosho research window off
researchzosho research                        # show the settings and what is running
```

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
  and goes on when it answers, within the run's time limit. Its log says how long it waited.
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
  cited paper that Crossref lists as retracted.

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
how long it took. It has one line for every time evidence was cut to fit the model's window. The
newest fifty traces are kept. Open this file when a write-up says something its evidence does not.
`RESEARCHZOSHO_TRACE=off` turns tracing off.

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

If the library has nothing, it says "I don't know" and offers to find out.

It follows two rules. It uses only the library's own tools: no commands, no reading your files, no web
outside a research run. And it does not make things up. A figure or a source that none of its look-ups
returned is marked under the reply as its guess.

Inside a conversation: `/new`, `/sessions`, `/resume <id>`, `/help`, `/quit`. Conversations are saved in
`catalog/chat/` and continue where they left off.

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

- `science` looks up citations from the record, tracks preprint versions, and
  writes BibTeX with `researchzosho bib <id>`.
- `software` is for questions about projects, libraries and tools. When a question names GitHub, open
  source, a repository or a package index, the research run searches GitHub and the package indexes
  directly, by best match and by most recent push. It judges a project by its last push, its licence and
  its own README, not by its stars, and gives the answer as a table.
- `genealogy` adds family relations, adoption and household heads included. It acts only on the
  family history you ask for: its own commands and pages, and a research run asked for in genealogy
  mode (`research ask … --genealogy`, the box on the Research page, `field: "genealogy"`, or a yes to the
  chat's question). Such a run follows genealogy's rules: it works from records, ties a record to a
  person by more than a name, notes searches that found nothing, and lists the records only the family
  can request. Everything else in the library works as if genealogy were off: a question that only
  looks like family history is told that genealogy mode is there, and runs as ordinary research; the
  map reads family relations only in the family's own claims and in the claims of genealogy-mode runs;
  the Family tree, Who is who and Decisions pages are in the menu once the library holds family work.
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

## 15. Running it as a service

```
researchzosho service install            # Linux, macOS or Windows; a user service, no administrator rights
researchzosho service status
researchzosho service restart            # asks first when a research run is going: a restart starts it over
researchzosho service uninstall
```

The service listens on `127.0.0.1:4649`. It runs the housekeeping at three each morning;
`--crew-hour` changes the hour. It writes its log to `~/.researchzosho/logs/librarian-serve.log`.
On Linux, `loginctl enable-linger <you>` keeps it running while you are logged out.

By default only the computer it runs on can reach it. To reach it from other machines on your network:

```
researchzosho service install --host 0.0.0.0
```

It then also answers on the computer's network address, for example `http://192.168.1.20:4649/`.
Read section 16 on sign-in before you do this. Do not expose the service to the internet. There is
no encryption, and tokens travel in the clear.

## 16. The web pages

Open `http://127.0.0.1:4649/` in a browser. The pages are:

| Page | What it shows |
|---|---|
| Home | A question box, the library's counts, recent changes, open questions, and what is running. |
| Ask | The same answer as `researchzosho ask`. |
| Search | Search of the library. |
| Entry | A claim, write-up, summary or saved document in full, with its sources, notes, review and connections. A report's page has "Go deeper": the questions it left open, each sent as an in-depth run with one button, and a box for your own follow-up; the run is given the report first. |
| Subjects | The subject list. |
| Inbox | Claims waiting for a decision, grouped by the report they came from and filtered nine ways. Tick any number, then accept, retire, or dispute them with a reason. |
| Open | The searches kept up to date, and the open questions, grouped by the report that left them and filtered eight ways: tick any number, then send them as runs, reorder, park, or drop them. |
| Changes | What changed, newest first. |
| Runs | What the housekeeping will do tonight; research runs, running and finished. "Pause the runner" holds every run at its next turn until "Resume"; "Stop" beside a run ends that one. |
| Research | Send a question, or sharpen it first. |
| Map | The map. |
| Who is who | Family history: for each person in your family, the people the web shows under their name, with links. You tick the one who is your relative, or say none of them is, or leave it for later, and you can write something about the person. One person at a time; leave whenever you like, and the Inbox says how many still wait. At `/who`, for people who may write. |
| Decisions | Family history: what only the family can settle, in one place. Names that may be one person, then two sources that disagree about one person, then who is who. Each answer says what it will do before you give it. At `/decide`, for people who may write. |

Every entry has "Read it: beginner · familiar · as written" and a Download line.

### Sign-in

As shipped, anyone can use the pages, and the home page says so. To require a sign-in before a
question can be sent:

```
researchzosho web signin on
researchzosho reader token <did>         # make a token for a person; they enter it once on the sign-in page
researchzosho web signin off
```

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
runs it beside an embedder. `docker run -i --rm -v $PWD/library:/library ghcr.io/wyrdsekai/researchzosho:0.5.3 mcp`
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
describes each one.

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

## 23. Not yet available

- Sharing one library between two households.
- Search reranking. It is built but off, because it did not improve results at this size.
