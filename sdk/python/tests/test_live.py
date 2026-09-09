"""Runs against a live daemon: RESEARCHZOSHO_URL (and optionally RESEARCHZOSHO_TOKEN)."""
import os
import pytest

from researchzosho import Librarian, LibraryError, CONTRACT

URL = os.environ.get("RESEARCHZOSHO_URL")
TOKEN = os.environ.get("RESEARCHZOSHO_TOKEN")

pytestmark = pytest.mark.skipif(not URL, reason="set RESEARCHZOSHO_URL to a running daemon")


def test_status_carries_provenance():
    s = Librarian(URL, TOKEN).status()
    assert s["contract"] == CONTRACT
    assert s["library_id"].startswith("lib_")
    assert "finding" in s["counts"]


def test_errors_carry_codes():
    with pytest.raises(LibraryError) as e:
        Librarian(URL, TOKEN).get("F-0000-nope")
    assert e.value.code == "not_found"
    assert e.value.status == 404


def test_submit_needs_sources_and_proof():
    anon = Librarian(URL)
    with pytest.raises(LibraryError) as e:
        anon.submit("A claim long enough to be refused for lacking sources.", [])
    assert e.value.code in ("forbidden", "no_sources")
