# researchzosho (Python)

**ResearchZosho** — 研究蔵書, the research holdings. Research Harness For The Rest Of Us.

A thin client for The Librarian over HTTP — the library protocol, contract 1.0
(`docs/LIBRARY_PROTOCOL.md`). Transport and types only; the library's one implementation lives
in the daemon. No dependencies beyond the standard library.

```python
from researchzosho import Librarian, LibraryError

lib = Librarian("http://127.0.0.1:4649", token="…")   # token from `researchzosho reader token <did>`
pkg = lib.ask("How do subtitlers handle keigo?")
if pkg["holds_nothing"]:
    print("nothing held")
for e in pkg["entries"]:
    print(e["id"], e["kind"], e["state"], e["title"])

v = lib.established("Keigo has no direct English equivalent")
print(v["verdict"], [a["id"] for a in v["accepted"]], [d["id"] for d in v["unreviewed"]])

text = lib.read("https://example.org/paper.pdf", max_chars=4000)["text"]

job = lib.research("What did Japanese sources say about keigo in subtitles after 2020?")
result = lib.wait(job["job_id"])          # an overnight ask; poll or wait
```

Every result carries `library_id`, `library_name`, `contract`. Errors raise `LibraryError` with
`.code` in `not_found | forbidden | no_sources | invalid_args | unavailable` and a message you
can show to a person.

`pip install researchzosho`. The CLI is `researchzosho` (`zosho` for short).
