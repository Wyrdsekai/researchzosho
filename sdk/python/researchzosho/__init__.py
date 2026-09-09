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
    """A protocol error: .code is stable (not_found, forbidden, no_sources, invalid_args, unavailable)."""

    def __init__(self, code: str, message: str, status: int = 0):
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status

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
                 collections: Optional[List[str]] = None, max_minutes: Optional[int] = None) -> Dict[str, Any]:
        """File an overnight ask. max_turns and max_minutes are ceilings, either, both or neither: the most
        model turns the whole run may spend, the most minutes it may take. With neither the run goes until
        the work is done. sub_questions (≤8) is the plan when you already have one.
        sources: both (shelves first) | shelves (the person's corpus only) | web; collections scope the shelves."""
        body: Dict[str, Any] = {"question": question, "mode": mode, "sources": sources}
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
            if j["state"] not in ("running", "queued"):
                return j
            if timeout_s is not None and time.time() - t0 > timeout_s:
                raise TimeoutError(f"job {job_id} still running after {timeout_s}s")
            time.sleep(poll_s)

    def run_crews(self) -> Dict[str, Any]:
        return self._post("crews/run", {})

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
            raise LibraryError(err.get("code", "unavailable"), err.get("message", str(e)), e.code) from None
        except urllib.error.URLError as e:
            raise LibraryError("unavailable", f"The Librarian did not answer at {self.base_url}: {e.reason}") from None
