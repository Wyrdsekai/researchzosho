# researchzosho (Python)

**ResearchZosho** — 研究蔵書, the research holdings. The Research Harness For The Rest Of Us.

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

# a document on the shelves translated, with what the checks still doubt listed at its end
plan = lib.translate("2026-11-10-clock.md", "Japanese", notes="end")
# the same in a voice of your own, in your own words
plan = lib.translate("2026-11-10-clock.md", "English", direction="as if you were Shakespeare")
print(plan["estimate"], plan["notes"])
done = lib.wait(plan["job_id"])

# a comic on the shelves: its pages are read and the translation is set into the balloons
try:
    plan = lib.translate("2026-11-13-volume.md", "English")
except LibraryError as e:
    if e.code != "confirm":
        raise
    # the comic helper is not set up on the library's machine: e.message says what a set-up fetches,
    # e.error["comic_helper"] lists each piece; show it to the person, and on their yes:
    plan = lib.translate("2026-11-13-volume.md", "English", setup=True)
# the library finds the language a comic is lettered in (plan["comic"]["from"]); from_ names it when it is another
plan = lib.translate("2026-11-13-volume.md", "English", from_="Korean")
# an English edition of a manga keeps its pages running from the right: say so
plan = lib.translate("2026-11-13-volume.md", "German", reads="right-to-left")
```

Every result carries `library_id`, `library_name`, `contract`. Errors raise `LibraryError` with
`.code` in `not_found | forbidden | no_sources | invalid_args | unavailable` and a message you
can show to a person.

`pip install researchzosho`. The CLI is `researchzosho` (`zosho` for short).
