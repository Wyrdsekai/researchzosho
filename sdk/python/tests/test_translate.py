"""The translate call against a stand-in server on the loopback: what reaches the wire, and the refusal."""
import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from researchzosho import Librarian, LibraryError

NOTES_SAID = ("The notes are end (what the checks still doubt numbered at the end of the document) or off "
              "(the document holds the translation alone; the note beside it lists what the checks still doubt).")


class _StandIn(BaseHTTPRequestHandler):
    seen = []

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])).decode("utf-8"))
        _StandIn.seen.append((self.path, body))
        notes = body.get("notes", "off")
        if notes not in ("end", "off"):
            out, status = {"error": {"code": "invalid_args", "message": NOTES_SAID}}, 400
        else:
            out, status = {"job_id": "J-0001", "to": body["to"], "quality": body.get("quality", "checked"), "notes": notes}, 200
        data = json.dumps(out).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


@pytest.fixture
def library():
    server = HTTPServer(("127.0.0.1", 0), _StandIn)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    _StandIn.seen.clear()
    try:
        yield Librarian(f"http://127.0.0.1:{server.server_port}")
    finally:
        server.shutdown()


def test_the_notes_reach_the_wire_as_asked(library):
    r = library.translate("2026-11-10-clock.md", "ja", quality="quick", notes="end")
    assert r["notes"] == "end"
    path, body = _StandIn.seen[-1]
    assert path == "/v1/translate"
    assert body["raw"] == "2026-11-10-clock.md" and body["to"] == "ja" and body["quality"] == "quick" and body["notes"] == "end"
    library.translate("2026-11-10-clock.md", "ja", as_="txt", notes="off")
    assert _StandIn.seen[-1][1]["notes"] == "off" and _StandIn.seen[-1][1]["as"] == "txt"


def test_left_out_the_library_decides(library):
    library.translate("2026-11-10-clock.md", "German")
    body = _StandIn.seen[-1][1]
    assert "notes" not in body and "quality" not in body and "as" not in body


def test_a_word_that_is_neither_is_refused(library):
    with pytest.raises(LibraryError) as e:
        library.translate("2026-11-10-clock.md", "ja", notes="sideways")
    assert e.value.code == "invalid_args" and e.value.status == 400 and e.value.message == NOTES_SAID
