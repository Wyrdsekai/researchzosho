"""check: every piece the helper is set up with, loaded once, so that a set-up ends knowing that each of them works on
this machine. One JSON object a line on stdout, as each piece is tried:
    {"piece": "reader-korean", "kind": "model", "ok": true, "sec": 0.2}
    {"piece": "yaml", "kind": "package", "ok": false, "why": "No module named 'yaml'", "module": "yaml", "sec": 0.0}
and a last line {"checked": 31, "failed": 1, "sec": 6.4}. The exit code is 1 when a piece failed, 0 when all loaded.
Nothing else is written to stdout: what a library prints goes to stderr.

The pieces, in this order:
  - the Python packages named with --import, each by the name it is imported by ("piece" is that name, "kind"
    "package"): imported; fugashi also splits a sentence with its dictionary, and budoux one with its Chinese model, as
    the setter uses them (kinsoku.py);
  - every thing of the manifest ("piece" is its name there, "kind" its kind): its files in the folder with the
    manifest's checksums, and then a model loaded as `read` loads it (the page detector, the reader of Japanese, the
    line finder, each line reader), a font opened and a letter of its writing drawn, a hyphenation pattern file read,
    the Chinese word list read.
When a package does not import, the manifest's things are not tried: those that need it would fail for its reason,
and one reason is what a person can act on. "module" is the module an import failure names, which may be one the
package itself needs."""
import importlib, json, os, sys, time
from . import models

# a letter of each writing a font's manifest entry may name: the font is asked to draw it
LETTER = {"latin": "A", "cyrillic": "Ж", "greek": "Ω", "japanese": "あ", "chinese-simplified": "汉", "chinese-traditional": "漢", "korean": "한"}

def package(module):
    """A package imported by its module's name, and for the two the setter ends lines by, used once as it uses them."""
    m = importlib.import_module(module)
    if module == "fugashi":
        sentence = "本を読む"                                    # 本を読む
        if "".join(w.surface for w in m.Tagger()(sentence)) != sentence: raise RuntimeError("its dictionary did not split a sentence into its words")
    elif module == "budoux":
        if not m.load_default_simplified_chinese_parser().parse("今天天气很好"): raise RuntimeError("its Chinese model split nothing")   # 今天天气很好

def thing(m, folder, loaded):
    """One thing of the manifest: its files as they were fetched, and then loaded as the helper loads it at work."""
    kind = m.get("kind", "model"); path = os.path.join(folder, m["files"][0]["path"])
    if kind == "model":                                          # the loader checks a model's files itself, once
        if m["name"] == "detector": loaded.detector()
        elif m.get("reads") == "japanese": loaded.reader("ja")
        elif m["name"] == "lines": loaded.finder()
        elif m.get("reads"): loaded.line_reader(m["reads"])
        else: loaded._checked(m["name"])                         # a model this check knows no loader for: its files at least
        return
    bad = models.unusable(folder, m["files"])
    if bad: raise models.Unloadable(m.get("said") or m["name"], bad)
    if kind == "font":
        from PIL import ImageFont
        font = ImageFont.truetype(path, 40, layout_engine=ImageFont.Layout.BASIC)
        for script in (m.get("script") or "").split():
            if script in LETTER and font.getmask(LETTER[script]).getbbox() is None: raise RuntimeError("it draws nothing for the letter " + LETTER[script])
    elif kind == "hyphenation":
        from .hyphen import Hyphenator
        more = [os.path.join(folder, f["path"]) for f in m["files"][1:]]
        if not Hyphenator(path, more[0] if more else None).patterns: raise RuntimeError("it holds no pattern")
    elif kind == "words":
        from .kinsoku import Words
        if not Words(path).count: raise RuntimeError("it holds no word")

def check(folder, threads=4, modules=()):
    out = sys.stdout; sys.stdout = sys.stderr                    # anything a library prints goes to stderr
    def say(obj):
        out.write(json.dumps(obj, ensure_ascii=False) + "\n"); out.flush()
    started = time.time(); tried = failed = 0
    def one(piece, kind, run):
        nonlocal tried, failed
        t = time.time(); tried += 1
        try:
            run(); say({"piece": piece, "kind": kind, "ok": True, "sec": round(time.time() - t, 1)})
        except Exception as e:
            failed += 1
            module = e.module if isinstance(e, models.Unloadable) else (getattr(e, "name", None) if isinstance(e, ImportError) else None)
            answer = {"piece": piece, "kind": kind, "ok": False, "why": e.why if isinstance(e, models.Unloadable) else models.why(e), "sec": round(time.time() - t, 1)}
            if module: answer["module"] = module
            say(answer)
    for module in modules: one(module, "package", lambda module=module: package(module))
    if not failed:
        loaded = models.Loaded(folder, threads=threads)
        for m in models.manifest()["models"]: one(m["name"], m.get("kind", "model"), lambda m=m: thing(m, folder, loaded))
    say({"checked": tried, "failed": failed, "sec": round(time.time() - started, 1)})
    return 1 if failed else 0
