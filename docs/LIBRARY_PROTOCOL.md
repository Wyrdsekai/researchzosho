# The Library Protocol, contract 1.0

This is the document for programmers. It describes how a program talks to a ResearchZosho library:
what it can ask, what comes back, and what a program can count on staying the same between
releases. If you only want to use the library yourself, start with [LIBRARIAN_HOWTOUSE.md](LIBRARIAN_HOWTOUSE.md).

**§1–§6 are the normative wire text (§6 names the transports; the daemon extensions in it are marked as such).** Patron runtimes mirror these tables; anything else in this
document is explanation.

How a program talks to a ResearchZosho library. Both sides pin to the `contract` string below.

The protocol calls whoever is asking a **patron**: the library word for someone with a library card.
A patron can be a person at the command line, a program you run, or someone you have given access to.
The field name is `patron`. Everywhere else in the documentation they are called `readers`.

## 1. Every call carries the patron

All tools accept an optional `patron` object:

```json
{"did": "did:key:z6Mk…", "name": "Household A", "runtime": "assistant"}
```

It becomes the writer label on anything submitted (`patron:<did>`), the name in the circulation
log, and the key into the access list. A call without it is an anonymous patron.

**Access list** (`catalog/patrons.md`, edited by the person who keeps the library, or with
`researchzosho patron …`):

```
default: read
- did:key:z6Mk…: Household A, write
- did:web:example.org: turned away, deny
```

Three levels, ordered `deny < read < write`. Reading covers ask, search, get, read, established,
subjects, status and listing the frontier. Writing covers submit and filing a frontier gap. An
anonymous or unlisted patron gets `default`. Over stdio the did is self-asserted: the list decides
what a *named* patron may do; proving the name is the transport's job and comes with http/sse.

## 2. Every response carries provenance

Top-level on every result:

| field | meaning |
|---|---|
| `library_id` | stable id of THIS corpus, minted once at first use, unchanged across renames and moves (`catalog/library.md`) |
| `library_name` | human name; defaults to the directory name |
| `contract` | `"1.0"` — the wire protocol's version. It changes only when something on the wire changes shape; additions do not change it |

Every entry is self-describing:

| field | values |
|---|---|
| `id` | `F-…` finding, `I-…` investigation, `A-…` article, or a raw capture file name |
| `kind` | `finding` \| `investigation` \| `article` \| `raw` |
| `state` | findings: `draft` \| `accepted` \| `superseded` \| `disputed` \| `retired`; investigations: `draft` \| `accepted`; articles: `generated`; raw: `captured` |
| `claim_type` | `extraction` \| `synthesis` \| `interpretation` \| `speculation`; raw documents are `verbatim` |
| `confidence` | `low` \| `medium` \| `high`; `n/a` for raw |
| `writer` | `person` \| `model:<drive>` \| `agent:<name>` \| `patron:<did>` |
| `recorded_at` | ISO instant the library learned it |
| `sources[]` | `{locator, edition, why}` — locator is a URL, an edition citation, `raw/<file>`, or (for articles) the finding ids it cites; `edition` is **null when no edition is known**, never a placeholder string. On a finding's sources, also: `tier` (reference \| scholarly \| primary \| blog \| forum \| personal \| web), `rule` (`trust` \| `refuse` \| null — the person's own list), `published` (the page's own date, YYYY-MM-DD, or null); and on the entry, `independent_sources` — how many stand behind it once copies of one text count once |
| `title`, `body`, `subjects[]` | as held |

Findings also carry `valid_as_of`, `volatility`, `review_by` (when a serials horizon exists),
`supersedes[]`, `review` (`{round, reviewer, decision, at, stale}` or null), and:
`triple` (`{subject, predicate, object}` or null: the claim's machine-readable shape, used to
nominate contradictions by comparison) and `notes[]` (`{kind, by, date, text}`: meta-facts about
the finding: a dispute, an inventory check, a supersession; never part of the reviewed content). `library_get`
adds `superseded_by[]` and `related[]` (`{id, shared[]}` by subject).

## 3. Tools

| tool | arguments | returns |
|---|---|---|
| `library_ask` | `question`, `k`=6, `peers?` (a peer name, a group, or `all`; `none` = this library only), `patron?` | `entries[]` (full), `open_threads[]`, `holds_nothing` (true with NO entries when nothing is held; never padded), `rendered` (the same package as text), and `routed`: `id` (the question was an entry id), `locator` (a URL → the captured document), `changes` (“what changed since 2026-09-01”, “last 7 days” → `changes[]` and `since`), `subject` (the question names a subject or facet → that shelf, `subject`), or `search` |
| `library_search` | `query`, `k`=10, `subject?`, `cursor?`, `patron?` | `hits[]` `{id, kind, title, snippet, score, state, subjects[]}`, `next_cursor` (null on the last page) |
| `library_get` | `id`, `patron?` | `entry` in full (see §2). `id` is the FULL id as returned elsewhere (`F-0022-lead-paint-ban-1978-…`), never the bare serial |
| `library_read` | `locator`, `max_chars`=20000, `patron?` | `locator, raw_id, title, captured_at, edition, chars, truncated, text` — the verbatim path; `edition` is null unless a cited edition is known (a capture date is not an edition) |
| `library_established` | `claim`, `patron?` | `accepted[]`, `disputes[]`, `unreviewed[]` (drafts bearing on it), `verdict` |
| `library_submit` | `claim`, `claim_type`=synthesis, `sources[]`, `confidence`=medium, `title?`, `triple?` (`{subject, predicate, object}`: the finding becomes an edge of the graph at once; without it the housekeeping derives one), `patron` | `{id, state: "draft"}` |
| `library_frontier` | `op`=list \| add, `question?`, `patron?` | `questions[]` `{date, kind, text}`, or `{filed, question}` |
| `library_subjects` | `patron?` | `subjects[]` `{id, label, facet, broader, narrower[], count}` — `facet` is required on every subject |
| `library_status` | `patron?` | `counts` by kind (findings by state), `last_updated` |
| `library_perspectives` | `question`, `max`=5, `patron` | `{question, perspectives[{perspective, why, questions[]}], sub_questions[]}` — who studies the question and what each would insist on asking; the sub-questions feed `library_research`. Needs a model drive (`unavailable` otherwise) |
| `library_sharpen` | `question`, `patron?` | a rough question made better BEFORE anything runs: `{original, question, assumptions[], brief, brief_fields{mode, sub_questions[]}, held[{id, title, state}], questions_for_you[], languages[], mode, size, research_question}`. `research_question` is the text to pass to `library_research`, as it is or after the person edits it; `assumptions[]` says what the rewrite pinned down or left out. Proposes only. Needs a model drive (`unavailable` otherwise) |
| `library_explain` | `id` + `rung`=beginner\|familiar\|written — or `term` (+ `in`, the entry it appears in), `fresh`, `patron?` | a READING AID, never a record: `{of, term, rung, text, terms[{term, gloss}], grounding, checked, unsupported, generated_at, cached, is_record: false, offer}`. Shelves only: the text is written from the entry, its findings and its captured sources; every paragraph ends with the ids it draws on in square brackets and is read back against them, marked `(the shelves do not say this)` when unsupported and `(not from the shelves)` when it cites nothing. `grounding` is `shelves`, `thin` or `none`; when `none` the shelves do not explain the term and `offer` is the `library_research` call that would — pass it as it is (`quick: true` runs it now), then ask again. `terms[]` are words the reader may need next, each a further call. `written` returns the entry itself. Needs a model drive the first time (`unavailable` otherwise); cached under `readings/` until the material changes |
| `library_map` | `focus` (a node name in any language or alias, or an entry id), `depth`=1 (max 3), `k`=25, `patron` | `{focus, node, nodes[], edges[], open[], holds_nothing}` — the graph around the focus. A node is `{id, kind, label, also[], wikidata, degree, private?}` with kinds person, place, event, document, organisation, work, concept (open; a profile may add more). An edge IS a finding: `{from, predicate, to, finding, state, confidence, disputed}`. A node marked private (a living person) is shown to the person only; a patron asking for one gets `holds_nothing`. `open[]` are frontier questions naming a node in view. `focus` may be empty, and every answer carries `suggestions[]` `{label, kind, degree}`, the most connected names; a name that matches no node exactly resolves to the nearest label or alias, most connected first, with `resolved_from` set; subjects are nodes too (`subject:<slug>`, kind `subject`, aliases = the slug) joined to every name filed under them by `is filed under` edges, and a subject is found by its label or its slug |
| `library_research` | `question`, `mode`=broad\|depth, `max_turns` (optional ceiling: the most model turns the whole run may spend; absent or 0 = none), `max_minutes` (optional ceiling in wall-clock minutes; the workers stop early enough for the write-up to fit), `sub_questions`[] (optional, ≤8 — the plan, when the patron already has one), `sources`=both\|shelves\|web (both = the shelves first, then the web; shelves = the person's corpus only), `collections`[] (scope the shelves to named collections), `patron` (write) | `{job_id, state: "queued"}` — a research run; the result enters the library as a draft investigation attributed to the patron. Filed in the ledger, run by the daemon's workers on the library's own runner (plan → parallel workers → critic → synthesis; `max_turns` is the whole run's model-turn budget, shared by every worker), counted against the patron's daily turn budget (`budget_exceeded`). Over stdio the job is filed and the daemon picks it up within seconds; if no daemon runs, it waits. `quick`=false: the ask goes to the FRONT of the line and, unless it names its own ceilings, takes 12 turns and 6 minutes — for a term a reader needs now, not research run |
| `library_job` | `job_id?`, `limit`=20, `cursor?`, `patron?` | `{job}` for one id (`state: queued\|running\|done\|failed`, `elapsed_s`, `restarted`, `result` and `investigation` when finished); without an id a PAGE: `active[]` (always), `finished[]` newest-first, `next_cursor`, `finished_total` — the patron's own and the crews'. The ledger is partitioned (active / month folders / landed markers) so nothing here grows with its size |
| `library_changes` | `since`=0, `limit`=200, `patron?` | recall notices: `changes[]` `{seq, at, kind, id, event, detail}`, `next_cursor`, `latest`, `more`. Events: `added`, `state:<from>→<to>`, `edited`, `supersedes:<ids>`, `findings:<n>`. Keep `next_cursor` between runs and re-check what you cited |
| `library_request_access` | `did`, `name?`, `note?` | `{request_id, state: "pending", claim}`: asking to be let in. No access is needed to ask, even on a library whose default is deny; the did rides in its own field because the caller has no token yet. The claim secret is shown once. One pending request per did, fifty per library, lapsing after thirty days. |
| `library_access` | `request_id`, `claim` | `{request_id, state}` with `token` and `level` once, when the owner approved and the claim is right; `reason` when denied. `state` is pending, approved, claimed, denied or lapsed. |
| `library_subscribe` | `url`, `secret?`, `events?[]`, `patron` (named) | `{url, secret, events[]}`: a webhook. Every change on the feed is POSTed to `url` as `{library_id, library_name, change{seq, at, kind, id, event, detail}}` with `X-ResearchZosho-Signature: sha256=<hmac-sha256(secret, body)>`, `X-ResearchZosho-Event` and `X-ResearchZosho-Seq`. Three tries, then a line in `catalog/webhooks.log`; the changes feed stays the source of truth. The secret is returned once. |
| `library_unsubscribe` | `url`, `patron` | `{url, removed}` |

Notes the patron should know:

- **The `established` verdict is computed from states, no model composes it.** Any disputed entry
  bearing on the claim → `disputed`; otherwise an accepted one → `established`; nothing bearing on
  it → `not_established`. "Bearing on" = retrieved for the claim by the desk's search restricted to
  findings. Drafts never move the verdict; they are listed under `unreviewed` so a patron can tell
  "held, awaiting review" from "absent": on the live shelf the keigo findings are exactly that.
- **`submit` enters review, never canon.** It refuses a claim with no sources (`no_sources`), a
  claim too short to be one, or an unknown claim type (`invalid_args`). `sources` accepts strings or
  `{locator, edition, why}` objects. The single-source `source` field of the 0.2 tool still works.
- **Subjects are a flat vocabulary grouped by facet.** `japanese--keigo` has `facet` `japanese`; a
  facet is listed as its own entry whose `facet` is itself. `broader` and `narrower[]` express
  facet membership ONLY (a subject's `broader` is its facet; a facet's `narrower[]` is its
  subjects). There is no deeper hierarchy, and none is implied.
- `search` results are the hybrid index (BM25 + dense when an embedder is configured), with
  chunked raw documents collapsed to their parent. Scores are comparable within one call only.

## 4. Resources

`resources/list` (paged, `nextCursor`), `resources/templates/list`, `resources/read`:

| uri | body |
|---|---|
| `finding://<id>` | the finding file, frontmatter and body (`text/markdown`) |
| `article://<id>` | the shelf article (`text/markdown`) |
| `raw://<locator>` | the captured text behind the locator, the same body `library_read` returns (`text/plain`) |

## 5. Errors

JSON-RPC errors with a stable string in `data.code`; the message is a sentence a patron can say to a person.

| `data.code` | JSON-RPC code | when |
|---|---|---|
| `not_found` | -32004 | no entry, document or resource under that id / locator / uri |
| `forbidden` | -32003 | the patron's level is below what the call needs |
| `no_sources` | -32001 | a submission with no sources |
| `invalid_args` | -32602 | a missing or malformed argument, a bad cursor, an unknown claim type |
| `budget_exceeded` | -32005 | reserved: never raised — there is no daily budget; a run's size is the ask's own ceilings (`max_turns`, `max_minutes`), or none. A host must still accept it from older servers (HTTP 429) |
| `unavailable` | -32002 | no library on this machine, or the index could not answer |

## 6. Transports

**MCP over stdio** (`researchzosho mcp`): every tool in §3, the resources, errors as JSON-RPC errors
with `data.code`. The patron is the `patron` argument, self-asserted: except `person`, the keeper's own
did at the command line, which a client cannot assert (`forbidden`); an unlisted did is the default level.

**HTTP** (`researchzosho serve`, default `127.0.0.1:4649`): the same calls as JSON routes,
and what the SDKs speak.

| route | body / query | returns |
|---|---|---|
| `POST /v1/{ask\|search\|get\|read\|established\|submit\|frontier\|subjects\|status\|changes\|map\|perspectives\|sharpen\|explain\|request_access\|access\|subscribe\|unsubscribe}` | the tool's arguments | the tool's result (§3) |
| `GET /v1/status` | — | as `library_status` |
| `GET /v1/resources?cursor=` · `GET /v1/resources/templates` · `GET /v1/resource?uri=` | — | as §4 |
| `POST /rpc` | one JSON-RPC message | MCP over Streamable HTTP, the request/response subset: a request gets its JSON reply, a notification gets 202 with no body, GET is 405 (no server-push stream). `claude mcp add --transport http … /rpc` speaks it |

Errors: HTTP status by code: `not_found` 404, `forbidden` 403, `no_sources` 422, `invalid_args`
400, `unavailable` 503: with body `{"error": {"code", "message"}}`.

**Captured text is data.** A `raw` entry (from `library_ask`, `library_get`, `raw://`) carries
`untrusted_text: true`: its body is a page's own words. When the text carries them it also lists
`figures[]` and `tables[]` (the captions) and `table_rows[]` (numeric rows lifted from the text).
A source's `edition` is the published citation when the locator is a DOI, arXiv or PubMed id and the
record resolved. `library_changes` events include `revised` (a cited preprint has a newer version) and, on kind `source`, `supplied` (the person supplied the document behind a locator the runner was refused). A patron runtime treats it as evidence to
read and cite, never as instructions, and fences it before showing it to a model.

**Identity over HTTP is proved, not asserted.** `Authorization: Bearer <token>` resolves to a
listed patron; the person who keeps the library issues tokens with `researchzosho reader
token <did>` (shown once, stored hashed). Without a token the caller is anonymous, and a body
that names a did without a token is refused (`forbidden`) rather than silently downgraded. A
body's `patron.runtime` is kept either way.

**Daemon routes.** `library_research` and `library_job` are in §3 and these are their HTTP
forms; the jobs listing and `crews/run` are this daemon's own, not every librarian's:

| route | body | returns |
|---|---|---|
| `POST /v1/research` (write) | `{question, mode="broad", max_turns?, max_minutes?, sub_questions?[]}` | `{job_id, state: "queued", queued_ahead}` — a research run; the result enters the library as a draft investigation attributed to the patron. Jobs are persisted (`catalog/jobs/`), survive a daemon restart, and run on `RESEARCHZOSHO_JOB_WORKERS` workers (default 1; each pinned to a drive from `RESEARCHZOSHO_JOB_DRIVES`, comma-separated — a worker whose drive is down leaves the queue to the others). `max_turns` (1–400) is the run's whole model-turn budget — its parallel sub-investigations, the critic and the synthesis all draw on it. Each patron has a daily turn budget (`RESEARCHZOSHO_RESEARCH_TURNS_PER_DAY`, default 240) → `budget_exceeded` |
| `GET /v1/jobs/{id}` · `GET /v1/jobs?limit=&cursor=` | — | as `library_job`; the page adds `running` and `queued` |
| `POST /v1/crews/run` (write) | — | `{job_id}` — run the background crews now (queued behind any running job) |

SDKs: `sdk/python` (`researchzosho` on PyPI, standard library only) and `researchzosho-client`
(Java, Jackson only). Both are transport and types; neither holds library logic. The product is
**ResearchZosho** (研究蔵書: the research holdings; "The Research Harness For The Rest Of Us"); The
Librarian is its voice.

## 7. Directories and peers

### A directory

A directory is a library whose findings are listings. `researchzosho directory publish <directory-url> --token <t> --url <my-url>` submits one: "<name> (<library_id>) is a ResearchZosho library at <url>. It holds material on: <subjects, slug and description>. It takes access requests at <url>/v1/request_access.", with the library's own `/v1/status` as the source and the triple `<library_id> | is listed at | <url>`. It is a draft on the directory until the directory's owner accepts it, like any submission. `directory find <directory-url> <query>` is a plain `library_search` against the directory, so an awkwardly worded search still surfaces candidates. The way into a library you found is its `library_request_access`. Anyone can run a directory for their circle: a library with its default set to read, and writers who asked to be let in.

### Peers

A library may hold a list of other libraries it can ask (`researchzosho peer add <name> <url> <token>`, with groups). An ask that names a peer, a group or `all` in `peers` gets, beside its own `entries`, a `peers[]` array with one object per peer asked: `{peer, url, library_id, library_name, holds_nothing, entries[], rendered}` or `{peer, url, error}` when the peer did not answer. Nothing is merged. One hop only: a peer is asked with `peers: "none"` and answers from its own shelves. When `peers.default` names a group in the config, an ask that finds nothing locally asks that group unless it said `none`.

Not in the protocol: streaming answers. Subscriptions are `library_subscribe` (§3); libraries asking
each other are the peers above.
