#!/usr/bin/env python3
"""Prints the chat template of every model file the program installs, for a person to read before a release.

A model file (GGUF) is numbers, with one exception: its chat template, a small program in the Jinja language that the model
server runs to lay a conversation out for the model. `model install` pins every file by its SHA-256, so a template cannot change
without the pin failing; this prints each one, so that what is pinned has been read.

The rows come from ModelServer.java itself (name, repository, file). Each file's first megabytes are fetched from Hugging Face
(a file is many gigabytes; the template stands in the header), or read from --dir when the files are on this machine.

    python3 scripts/chat-templates.py              # every template, in full
    python3 scripts/chat-templates.py --sums       # one line per file: the template's length and SHA-256
    python3 scripts/chat-templates.py --dir ~/.researchzosho/models
"""
import hashlib, os, re, struct, sys, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(ROOT, "librarian/src/main/java/org/researchzosho/librarian/ModelServer.java")
AGENT = "researchzosho.org release check (https://researchzosho.org)"
SIZES = {0: 1, 1: 1, 2: 2, 3: 2, 4: 4, 5: 4, 6: 4, 7: 1, 10: 8, 11: 8, 12: 8}


def rows():
    text = open(SOURCE, encoding="utf-8").read()
    return re.findall(r'new Row\("([^"]+)",\s*\d+,\s*"([^"]+)",\s*"([^"]+\.gguf)",\s*"([0-9a-f]{64})"', text)


class Short(Exception):
    pass


def template(head):
    """The value of tokenizer.chat_template in a GGUF header; Short when the bytes end before it."""
    if head[:4] != b"GGUF":
        raise ValueError("not a GGUF file")
    at = 8
    def take(n):
        nonlocal at
        if at + n > len(head):
            raise Short()
        b = head[at:at + n]; at += n
        return b
    def string():
        (n,) = struct.unpack("<Q", take(8))
        return take(n)
    def skip(kind):
        nonlocal at
        if kind == 8:
            string()
        elif kind == 9:
            (inner,) = struct.unpack("<I", take(4)); (count,) = struct.unpack("<Q", take(8))
            if inner in SIZES:
                take(SIZES[inner] * count)
            else:
                for _ in range(count):
                    skip(inner)
        else:
            take(SIZES[kind])
    take(8)                                    # the number of tensors
    (pairs,) = struct.unpack("<Q", take(8))
    for _ in range(pairs):
        key = string().decode("utf-8", "replace"); (kind,) = struct.unpack("<I", take(4))
        if key == "tokenizer.chat_template" and kind == 8:
            return string().decode("utf-8", "replace")
        skip(kind)
    return None


def head_of(repo, name, folder, size):
    if folder:
        with open(os.path.join(os.path.expanduser(folder), name), "rb") as f:
            return f.read(size)
    req = urllib.request.Request("https://huggingface.co/%s/resolve/main/%s" % (repo, name), headers={"User-Agent": AGENT, "Range": "bytes=0-%d" % (size - 1)})
    with urllib.request.urlopen(req, timeout=180) as r:
        return r.read()


def main():
    folder = sys.argv[sys.argv.index("--dir") + 1] if "--dir" in sys.argv else None
    sums = "--sums" in sys.argv
    failed = 0
    for name, repo, file, sha in rows():
        size, text = 16 << 20, None
        try:
            while True:
                try:
                    text = template(head_of(repo, file, folder, size)); break
                except Short:
                    if size >= 512 << 20: raise ValueError("no end of the header in the first 512 MB")
                    size *= 2
        except Exception as e:                  # a file that is not there, a network that is not: said, and the others go on
            print("%s  %s: could not be read (%s)" % (name, file, e)); failed += 1; continue
        if text is None:
            print("%s  %s: the file has no chat template (the server falls back to its own)" % (name, file)); continue
        digest = hashlib.sha256(text.encode("utf-8")).hexdigest()
        print("%s  %s  (file pinned at %s…)  template: %d characters, sha256 %s" % (name, file, sha[:12], len(text), digest))
        if not sums:
            print("-" * 100); print(text); print("-" * 100); print()
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
