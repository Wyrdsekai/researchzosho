"""ResearchZosho — client for The Librarian, the library protocol (contract 1.3).

Transport and types only. Every method returns the daemon's JSON as a dict; errors raise
LibraryError with the protocol's stable code.
"""
from __future__ import annotations

import json
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Dict, List, Optional, Union

CONTRACT = "1.0"

__all__ = ["Librarian", "LibraryError", "CONTRACT"]


class LibraryError(Exception):
    """A protocol error: .code is stable (not_found, forbidden, no_sources, invalid_args, unavailable, declined, confirm)."""

    def __init__(self, code: str, message: str, status: int = 0, error: Optional[Dict[str, Any]] = None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status
        # the error as the library sent it: beside code and message, what a refusal carries as data
        # (a confirm for a comic lists under comic_helper what setting the comic helper up fetches)
        self.error = error or {}

    def __str__(self) -> str:  # a sentence a person can read
        return f"{self.message} [{self.code}]"


class Librarian:
    """One library at one URL. Pass the bearer token that proves your did; omit it to be anonymous."""

    def __init__(self, base_url: str = "http://127.0.0.1:4649", token: Optional[str] = None,
                 runtime: str = "python", timeout: float = 60.0):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.runtime = runtime
        self.timeout = timeout

    # ---- the nine calls ----

    def ask(self, question: str, k: int = 6) -> Dict[str, Any]:
        return self._post("ask", {"question": question, "k": k})

    def search(self, query: str, k: int = 10, subject: Optional[str] = None,
               cursor: Optional[str] = None) -> Dict[str, Any]:
        args: Dict[str, Any] = {"query": query, "k": k}
        if subject:
            args["subject"] = subject
        if cursor:
            args["cursor"] = cursor
        return self._post("search", args)

    def search_all(self, query: str, subject: Optional[str] = None, page: int = 50) -> List[Dict[str, Any]]:
        """Follow next_cursor to the end."""
        hits: List[Dict[str, Any]] = []
        cursor = None
        while True:
            r = self.search(query, k=page, subject=subject, cursor=cursor)
            hits.extend(r["hits"])
            cursor = r.get("next_cursor")
            if not cursor:
                return hits

    def get(self, entry_id: str) -> Dict[str, Any]:
        return self._post("get", {"id": entry_id})["entry"]

    def read(self, locator: str, max_chars: int = 20000) -> Dict[str, Any]:
        return self._post("read", {"locator": locator, "max_chars": max_chars})

    def established(self, claim: str) -> Dict[str, Any]:
        return self._post("established", {"claim": claim})

    def submit(self, claim: str, sources: List[Union[str, Dict[str, str]]], claim_type: str = "synthesis",
               confidence: str = "medium", title: Optional[str] = None,
               triple: Optional[Dict[str, str]] = None) -> Dict[str, Any]:
        """triple = {"subject", "predicate", "object"} makes the finding an edge of the graph (library_map)."""
        args: Dict[str, Any] = {"claim": claim, "sources": sources, "claim_type": claim_type, "confidence": confidence}
        if title:
            args["title"] = title
        if triple:
            args["triple"] = triple
        return self._post("submit", args)

    def frontier(self) -> List[Dict[str, Any]]:
        return self._post("frontier", {"op": "list"})["questions"]

    def frontier_add(self, question: str) -> Dict[str, Any]:
        return self._post("frontier", {"op": "add", "question": question})

    def subjects(self) -> List[Dict[str, Any]]:
        return self._post("subjects", {})["subjects"]

    def status(self) -> Dict[str, Any]:
        return self._post("status", {})

    def changes(self, since: Union[str, int] = 0, limit: int = 200) -> Dict[str, Any]:
        """Recall notices after a cursor: {changes[], next_cursor, latest, more}. Keep next_cursor between runs."""
        return self._post("changes", {"since": str(since), "limit": limit})

    # ---- resources ----

    def resources(self, cursor: Optional[str] = None) -> Dict[str, Any]:
        return self._get("resources", {"cursor": cursor} if cursor else {})

    def resource(self, uri: str) -> str:
        return self._get("resource", {"uri": uri})["contents"][0]["text"]

    # ---- research and job are contract 1.2; the jobs page and crews/run are the daemon's own ----

    def perspectives(self, question: str, max: int = 5) -> Dict[str, Any]:
        """Who studies this and what each would ask: {perspectives[], sub_questions[]} for research()."""
        return self._post("perspectives", {"question": question, "max": max})

    def map(self, focus: str, depth: int = 1, k: int = 25) -> Dict[str, Any]:
        """The graph around a node name or an entry id: nodes {id, kind, label, also, wikidata}, edges = findings, open questions."""
        return self._post("map", {"focus": focus, "depth": depth, "k": k})

    def research(self, question: str, mode: str = "broad", max_turns: Optional[int] = None,
                 sub_questions: Optional[List[str]] = None, sources: str = "both",
                 collections: Optional[List[str]] = None, max_minutes: Optional[int] = None,
                 field: Optional[str] = None) -> Dict[str, Any]:
        """File an overnight ask. max_turns and max_minutes are ceilings, either, both or neither: the most
        model turns the whole run may spend, the most minutes it may take. With neither the run goes until
        the work is done. sub_questions (≤8) is the plan when you already have one.
        sources: both (shelves first) | shelves (the person's corpus only) | web; collections scope the shelves.
        field: the field the run is to be, one of the library's fields by name. A field that acts only when asked
        for (family history, say) joins the run only this way; without it, a question that looks like one of its
        questions comes back with suggestion {field, why, how} in the result and is researched as ordinary research."""
        body: Dict[str, Any] = {"question": question, "mode": mode, "sources": sources}
        if field:
            body["field"] = field.strip()
        if max_turns:
            body["max_turns"] = int(max_turns)
        if max_minutes:
            body["max_minutes"] = int(max_minutes)
        if sub_questions:
            body["sub_questions"] = list(sub_questions)[:8]
        if collections:
            body["collections"] = list(collections)[:8]
        return self._post("research", body)

    def jobs(self, limit: int = 20, cursor: Optional[str] = None) -> Dict[str, Any]:
        """A page of this patron's jobs: {active[], finished[] (newest first), next_cursor, finished_total, running, queued}."""
        params: Dict[str, Any] = {"limit": limit}
        if cursor:
            params["cursor"] = cursor
        return self._get("jobs", params)

    def job(self, job_id: str) -> Dict[str, Any]:
        return self._get(f"jobs/{urllib.parse.quote(job_id, safe='')}", {})

    def wait(self, job_id: str, poll_s: float = 15.0, timeout_s: Optional[float] = None) -> Dict[str, Any]:
        """Block until the job leaves 'running'. Overnight asks take hours; leave timeout_s None."""
        t0 = time.time()
        while True:
            j = self.job(job_id)
            if j["state"] not in ("running", "queued", "offered"):   # offered: it waits for the person's answer to the chat's question
                return j
            if timeout_s is not None and time.time() - t0 > timeout_s:
                raise TimeoutError(f"job {job_id} still running after {timeout_s}s")
            time.sleep(poll_s)

    def run_crews(self) -> Dict[str, Any]:
        return self._post("crews/run", {})

    # ---- a document translated (library_translate) ----

    def translate(self, raw: str, to: str, as_: Optional[str] = None, quality: Optional[str] = None,
                  notes: Optional[str] = None, direction: Optional[str] = None,
                  comic: Optional[bool] = None, setup: bool = False, from_: Optional[str] = None,
                  reads: Optional[str] = None) -> Dict[str, Any]:
        """A document on the shelves (raw, its entry) translated into a language (to: a name or a code) and
        written out, as a job: the plan at once ({job_id, ..., notes, ...}); wait(job_id) follows the run,
        whose result names the file. as_: md | txt | pdf | docx | epub (default: the input's format).
        quality: checked (the default) | quick | reviewed | thorough. notes: where what the checks still
        doubt goes: "end" numbers each such passage in the document and lists them in a last section, "off"
        leaves the document to the translation alone; the note beside it lists them either way. Left out,
        the library's setting translate_notes decides; the result's notes says which the run was filed with.
        A word that is neither is refused (LibraryError, code invalid_args). direction: your own words for the
        translation's voice, tone or wording ("as if you were Shakespeare", "this is a comedy, keep it whimsical"),
        at most 400 characters; the translator, the reviser and the reader are told it, and numbers, dates,
        addresses, citations, names and glossary terms are kept and checked as in any run. A longer one is
        refused (LibraryError, code invalid_args).

        A comic (manga, manhwa, manhua, a web comic's strips, a comic book, a graphic novel) is translated
        too: a CBZ, a CBR or a ZIP of page pictures is one by itself; comic=True says a PDF of page pictures
        is one, comic=False that a file is a document. Its pages are read by the comic helper on the
        library's machine and the translation is set into the speech balloons (as_: cbz | pdf); the
        result's comic says how many pages it has, whether the helper is set up, and the language of its
        lettering (comic["from"], and comic["from_how"]: named, sampled, or at-start when it is found as
        the run starts). Where the helper is not set up, the call is refused (LibraryError, code confirm):
        the message says what a set-up fetches (about a gigabyte), error["comic_helper"] lists each piece
        with its size and licence, and the same call with setup=True is the person's yes: the run sets the
        helper up first.

        from_: the language the document is written in, or the comic is lettered in (a name or a code:
        "Korean", "ko"). Left out, the library finds it, from a document's text or on a sample of a comic's
        pages. A comic is read from Japanese, Chinese, Korean and the languages of the Latin, Cyrillic and
        Greek alphabets, and lettered in the same; a language outside those, and a file already in the
        language asked for, are refused (LibraryError, code invalid_args) with the reason.

        reads: the way a comic's pages are read, when you know it: "right-to-left" (an English, French or
        Korean edition of a manga keeps its pages running from the right) or "left-to-right" ("rtl" and
        "ltr" are taken too). Left out, the library finds it: a Japanese comic from the right, a Chinese
        one from the right when its lettering runs in columns, any other from the left. The result's
        comic["reads"] says the way and comic["reads_how"] how it is known (said, found, at-start).
        comic["second_reading"] says whether the translator reads each balloon's lettering a second
        time: "twice"; "at-start" while that is to be found; or "quick", "blind", "japanese", the comic
        helper alone reading it, and why."""
        body: Dict[str, Any] = {"raw": raw, "to": to}
        if from_:
            body["from"] = from_
        if reads:
            body["reads"] = reads
        if as_:
            body["as"] = as_
        if quality:
            body["quality"] = quality
        if notes:
            body["notes"] = notes
        if direction:
            body["direction"] = direction
        if comic is not None:
            body["comic"] = "on" if comic else "off"
        if setup:
            body["setup"] = True
        return self._post("translate", body)

    # ---- plumbing ----

    def _headers(self) -> Dict[str, str]:
        h = {"Content-Type": "application/json", "Accept": "application/json"}
        if self.token:
            h["Authorization"] = f"Bearer {self.token}"
        return h

    def _post(self, route: str, args: Dict[str, Any]) -> Dict[str, Any]:
        body = dict(args)
        body["patron"] = {"runtime": self.runtime}   # the did comes from the token, never asserted here
        req = urllib.request.Request(f"{self.base_url}/v1/{route}", data=json.dumps(body).encode("utf-8"),
                                     headers=self._headers(), method="POST")
        return self._send(req)

    def _get(self, route: str, params: Dict[str, Any]) -> Dict[str, Any]:
        url = f"{self.base_url}/v1/{route}"
        if params:
            url += "?" + urllib.parse.urlencode(params)
        req = urllib.request.Request(url, headers=self._headers(), method="GET")
        return self._send(req)

    def _send(self, req: urllib.request.Request) -> Dict[str, Any]:
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as res:
                return json.loads(res.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            try:
                err = json.loads(e.read().decode("utf-8")).get("error", {})
            except Exception:
                err = {}
            raise LibraryError(err.get("code", "unavailable"), err.get("message", str(e)), e.code, err) from None
        except urllib.error.URLError as e:
            raise LibraryError("unavailable", f"The Librarian did not answer at {self.base_url}: {e.reason}") from None
