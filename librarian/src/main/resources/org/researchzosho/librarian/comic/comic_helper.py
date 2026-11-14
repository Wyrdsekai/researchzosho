"""ResearchZosho's comic helper: reads the lettering of comic pages and sets text back into the balloons.

This file starts the package that lies beside it (the folder comic/: its manifest names the models, the fonts, the
hyphenation patterns and the Chinese word list it fetches, each with its source, size, checksum and licence). The program runs it with python -I,
which puts no folder of its own on the import path, so the folder is put there here.

  comic_helper.py fetch --to MODELS          download exactly the manifest's files, checksums checked
  comic_helper.py models --in MODELS         print the manifest and the state of a folder
  comic_helper.py check --models MODELS …    load every piece once and say which does not load (comic/check.py)
  comic_helper.py serve --models MODELS …    one process answering JSON lines on stdin and stdout (comic/serve.py):
                                             read (any language it lists at start), script, cut, join, set, picture

It runs on the processor and calls no model server; no page leaves the machine through it.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from comic.__main__ import main  # noqa: E402

if __name__ == "__main__":
    sys.exit(main())
