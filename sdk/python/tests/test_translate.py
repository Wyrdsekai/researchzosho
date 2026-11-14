"""The translate call against a stand-in server on the loopback: what reaches the wire, and the refusal."""
import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from researchzosho import Librarian, LibraryError

NOTES_SAID = ("The notes are end (what the checks still doubt numbered at the end of the document) or off "
              "(the document holds the translation alone; the note beside it lists what the checks still doubt).")


COMIC_SAID = ("Reading a comic's pages needs the comic helper, which is not set up on this machine yet. "
              "Nothing was fetched, and the comic was not translated: send the same call with setup=true "
              "to set the comic helper up and translate it.")

DIRECTION_SAID = "A direction for a translation is at most 400 characters; this one has 401. Say it in fewer words."


class _StandIn(BaseHTTPRequestHandler):
    seen = []

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])).decode("utf-8"))
        _StandIn.seen.append((self.path, body))
        notes = body.get("notes", "off")
        if body.get("raw", "").endswith(".cbz.md") and not body.get("setup"):   # a comic where the comic helper is not set up
            out, status = {"error": {"code": "confirm", "message": COMIC_SAID,
                                     "comic_helper": {"set_up": False, "download_bytes": 984000000,
                                                      "fetches": [{"name": "the page reader", "bytes": 444000000, "licence": "Apache 2.0"}]}}}, 422
        elif notes not in ("end", "off"):
            out, status = {"error": {"code": "invalid_args", "message": NOTES_SAID}}, 400
        elif len(body.get("direction", "")) > 400:
            out, status = {"error": {"code": "invalid_args", "message": DIRECTION_SAID}}, 400
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


def test_a_direction_reaches_the_wire_and_none_is_sent_when_left_out(library):
    library.translate("2026-11-10-clock.md", "ja", quality="quick", direction="as if you were Shakespeare")
    assert _StandIn.seen[-1][1]["direction"] == "as if you were Shakespeare"
    library.translate("2026-11-10-clock.md", "ja", quality="quick")
    assert "direction" not in _StandIn.seen[-1][1]


def test_a_direction_past_its_length_is_refused(library):
    with pytest.raises(LibraryError) as e:
        library.translate("2026-11-10-clock.md", "ja", direction="x" * 401)
    assert e.value.code == "invalid_args" and e.value.status == 400 and e.value.message == DIRECTION_SAID


def test_a_comic_is_refused_with_what_a_set_up_fetches_and_filed_on_the_yes(library):
    with pytest.raises(LibraryError) as e:
        library.translate("2026-11-13-volume.cbz.md", "English", quality="quick")
    assert e.value.code == "confirm" and e.value.status == 422 and e.value.message == COMIC_SAID
    helper = e.value.error["comic_helper"]
    assert helper["set_up"] is False and helper["fetches"][0]["licence"] == "Apache 2.0"
    assert "setup" not in _StandIn.seen[-1][1] and "comic" not in _StandIn.seen[-1][1]
    r = library.translate("2026-11-13-volume.cbz.md", "English", quality="quick", setup=True)
    assert r["job_id"] == "J-0001" and _StandIn.seen[-1][1]["setup"] is True


def test_what_is_said_of_the_file_reaches_the_wire_as_on_or_off(library):
    library.translate("2026-11-13-pages.pdf.md", "English", comic=True, as_="pdf")
    assert _StandIn.seen[-1][1]["comic"] == "on" and _StandIn.seen[-1][1]["as"] == "pdf"
    library.translate("2026-11-13-pictures.zip.md", "English", comic=False)
    assert _StandIn.seen[-1][1]["comic"] == "off"
    library.translate("2026-11-10-clock.md", "ja")
    assert "comic" not in _StandIn.seen[-1][1] and "setup" not in _StandIn.seen[-1][1]
    assert LibraryError("unavailable", "no answer").error == {}


def test_the_language_a_comic_is_lettered_in_reaches_the_wire_as_from(library):
    library.translate("2026-11-13-volume.cbz.md", "English", quality="quick", setup=True, from_="Korean")
    assert _StandIn.seen[-1][1]["from"] == "Korean" and "from_" not in _StandIn.seen[-1][1]
    library.translate("2026-11-10-clock.md", "ja", from_="de")
    assert _StandIn.seen[-1][1]["from"] == "de"
    library.translate("2026-11-10-clock.md", "ja")
    assert "from" not in _StandIn.seen[-1][1]


def test_the_way_a_comics_pages_are_read_reaches_the_wire_as_reads(library):
    library.translate("2026-11-13-volume.cbz.md", "German", quality="quick", setup=True, reads="right-to-left")
    assert _StandIn.seen[-1][1]["reads"] == "right-to-left"
    library.translate("2026-11-13-volume.cbz.md", "German", quality="quick", setup=True, from_="en", reads="ltr")
    assert _StandIn.seen[-1][1]["reads"] == "ltr" and _StandIn.seen[-1][1]["from"] == "en"
    library.translate("2026-11-13-volume.cbz.md", "German", quality="quick", setup=True)
    assert "reads" not in _StandIn.seen[-1][1]
