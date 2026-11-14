"""Where the tests find what the package does not ship. All from the environment; nothing is assumed about the machine.
  COMIC_MODELS     the folder `comic_helper.py fetch --to` filled                        (required for the reading tests)
  COMIC_FONT       a Latin font file for setting; the tests were written with Comic Neue Bold (required for set tests)
  COMIC_MORE_FONT  a font with Cyrillic letters, e.g. Comic Relief Bold                  (required for the second-font test)
  COMIC_CJK_FONT   a Japanese font file to DRAW the test pages with, e.g. Noto Sans CJK JP Bold (required for the reading tests;
                   the Korean and Chinese pages are drawn with it too, so it has to hold hangul and both Chinese forms, as Noto Sans CJK does)
  COMIC_CJK_INDEX  the face index when that file is a collection (default 0)
  COMIC_THREADS    CPU threads (default 4)
  COMIC_DIGITS_N   size of the sideways-digit set (default 200, the measured size)
The package under test is the one the program ships: librarian/src/main/resources/org/researchzosho/librarian/comic/.
Gradle does not run these: they need the fetched models. From this folder:
  COMIC_MODELS=… COMIC_FONT=… COMIC_MORE_FONT=… COMIC_CJK_FONT=… python -m unittest discover -s . -v"""
import os, sys, unittest
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", "..", "..", "main", "resources", "org", "researchzosho", "librarian", "comic"))
sys.path.insert(0, ROOT); sys.path.insert(0, HERE)
MODELS = os.environ.get("COMIC_MODELS"); FONT = os.environ.get("COMIC_FONT"); MORE_FONT = os.environ.get("COMIC_MORE_FONT"); CJK = os.environ.get("COMIC_CJK_FONT")
CJK_INDEX = int(os.environ.get("COMIC_CJK_INDEX", "0")); THREADS = int(os.environ.get("COMIC_THREADS", "4")); DIGITS_N = int(os.environ.get("COMIC_DIGITS_N", "200"))
def need(*names):
    missing = [n for n in names if not os.environ.get(n)]
    return unittest.skipIf(bool(missing), "set " + ", ".join(missing) + " to run this test")
_loaded = None
def loaded_models():
    """The models of COMIC_MODELS (comic.models.Loaded) for the whole test run: the page detector, and each language's
    reader loaded when a test first asks for it."""
    global _loaded
    if _loaded is None:
        from comic import models
        _loaded = models.Loaded(MODELS, threads=THREADS)
    return _loaded
def loaded():
    """(detector, the Japanese reader), loaded once for the whole test run."""
    return loaded_models().detector(), loaded_models().reader("ja")
