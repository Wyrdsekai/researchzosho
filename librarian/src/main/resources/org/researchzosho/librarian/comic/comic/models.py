"""What this package fetches: the manifest (comic/manifest.json: the models, the fonts, the hyphenation patterns, the
Chinese word list, each with its source, revision, size, checksum and licence), fetching exactly its files into a folder, and loading from that
folder. The package ships no weights and no font.
The models: the page detector (text and balloons); the reader of Japanese (manga-ocr, "reads": "japanese"); and for
every other language the line finder ("lines") and one line reader a writing system ("reads": latin, cyrillic, greek,
korean, chinese). Loaded (below) loads each at its first use, so a job loads only what its language needs."""
import hashlib, json, os, sys, urllib.request

class Unloadable(RuntimeError):
    """A piece of the helper that does not load on this machine: a model whose file is missing or is not as it was
    fetched, a reader whose Python package does not import. It is no page's failure, since every page would fail the
    same way: `serve` answers it with the error type "Unloadable", and a set-up's `check` lists it. `what` says the
    piece as the manifest says it, `why` the reason in one line, `module` the module an import failure names (None
    for any other reason). An import failure is said as the package's, not the piece's: the look at a comic's writing
    loads every line reader, and with PyYAML gone a Korean comic was told that the model that reads Chinese lettering
    does not load."""
    def __init__(self, what, cause):
        self.what = what; self.why = why(cause)
        self.module = (getattr(cause, "name", None) or None) if isinstance(cause, ImportError) else None
        super().__init__(f"a Python package the helper needs does not load ({self.why})" if self.module else f"{what} does not load ({self.why})")

def why(cause):
    """A reason in one line: an exception's own words, or its class's name where it has none; a string as it is."""
    if isinstance(cause, str): return cause
    return (" ".join(str(cause).split()) or type(cause).__name__)[:300]

def unusable(folder, files):
    """Why a thing's files cannot be used, in a clause ("its file a/b.onnx is missing", "its file a/b.onnx is not as
    it was fetched"); None when each is there with the manifest's checksum."""
    missing = [f["path"] for f in files if not os.path.exists(os.path.join(folder, f["path"]))]
    changed = [f["path"] for f in files if f["path"] not in missing and sha256_of(os.path.join(folder, f["path"])) != f["sha256"]]
    said = []
    if missing: said.append(("its file " if len(missing) == 1 else "its files ") + ", ".join(missing) + (" is missing" if len(missing) == 1 else " are missing"))
    if changed: said.append(("its file " if len(changed) == 1 else "its files ") + ", ".join(changed) + (" is not as it was fetched" if len(changed) == 1 else " are not as they were fetched"))
    return " and ".join(said) or None

def manifest():
    with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "manifest.json"), encoding="utf-8") as f:
        return json.load(f)

def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""): h.update(chunk)
    return h.hexdigest()

def check(folder):
    """[(path, state)] for every file of the manifest: 'ok', 'missing' or 'wrong checksum'."""
    out = []
    for m in manifest()["models"]:
        for f in m["files"]:
            p = os.path.join(folder, f["path"])
            out.append((f["path"], "missing" if not os.path.exists(p) else ("ok" if sha256_of(p) == f["sha256"] else "wrong checksum")))
    return out

def fetch(folder, user_agent="comic-fetch/0.1", log=sys.stderr):
    """Downloads every file of the manifest that is not already in the folder with the right checksum."""
    for m in manifest()["models"]:
        for f in m["files"]:
            p = os.path.join(folder, f["path"]); os.makedirs(os.path.dirname(p), exist_ok=True)
            if os.path.exists(p) and sha256_of(p) == f["sha256"]:
                print("have    ", f["path"], file=log); continue
            req = urllib.request.Request(f["url"], headers={"User-Agent": user_agent}); tmp = p + ".part"
            with urllib.request.urlopen(req, timeout=120) as r, open(tmp, "wb") as o:
                done = said = 0
                while True:
                    chunk = r.read(1 << 20)
                    if not chunk: break
                    o.write(chunk); done += len(chunk)
                    if done - said >= 64 << 20:                 # a large file says how far it is, for whoever waits
                        said = done; print("fetching", f["path"], done, f["size"], file=log, flush=True)
            got = sha256_of(tmp)
            if got != f["sha256"] or os.path.getsize(tmp) != f["size"]:
                os.remove(tmp); raise RuntimeError(f'{f["path"]}: the download does not match the manifest (sha256 {got})')
            os.replace(tmp, p); print("fetched ", f["path"], f["size"], "bytes", file=log, flush=True)

def versions():
    import importlib.metadata as md
    out = {m["name"]: m["revision"][:12] for m in manifest()["models"]}
    for pk in ("manga-ocr", "onnxruntime", "torch", "transformers", "budoux", "fugashi"):      # budoux, fugashi: where set ends the lines of Chinese and Japanese (kinsoku.py); None when not installed
        try: out[pk] = md.version(pk)
        except Exception: out[pk] = None
    return out

def needed(language=None):
    """The paths `read` cannot do without: the files of the entries that are models (fonts, hyphenation patterns and the word list are `set`'s).
    With a language, only what reading that language loads: the page detector, and manga-ocr for Japanese or, for any
    other language, the line finder and the line reader of its writing system (the entry whose "reads" names it)."""
    ms = [m for m in manifest()["models"] if m.get("kind", "model") == "model"]
    if language is not None:
        from .reader import system_of
        system = system_of(language)
        ms = [m for m in ms if m["name"] == "detector" or m.get("reads") == system or (system != "japanese" and m["name"] == "lines")]
    return {f["path"] for m in ms for f in m["files"]}

def fonts(folder):
    """The fonts of the manifest as `fetch` put them in the folder, the Latin one first: [(script, path)]. A font's
    script is a space-separated list of latin, cyrillic, greek, japanese, chinese-simplified, chinese-traditional,
    korean: a caller names the target's own font first and the others after it."""
    out = [(m.get("script", ""), os.path.join(folder, m["files"][0]["path"])) for m in manifest()["models"] if m.get("kind") == "font"]
    return sorted(out, key=lambda x: x[0] != "latin")

class Loaded:
    """The models of a folder that `fetch` filled, each loaded at its first use and kept: the page detector, and per
    writing system its reader (manga-ocr for Japanese; for the others one line finder shared by all and a line reader
    each). A file is loaded only after its checksum matches the manifest's."""
    def __init__(self, folder, threads=4):
        self.folder, self.threads = folder, threads; self._detector = self._finder = None; self._readers = {}; self._ok = set()

    def _checked(self, name):
        """The folder of the manifest entry `name`, its files checked once; Unloadable when one is missing or changed."""
        m = next(m for m in manifest()["models"] if m["name"] == name)
        if name not in self._ok:
            bad = unusable(self.folder, m["files"])
            if bad: raise Unloadable(m.get("said") or name, bad)
            self._ok.add(name)
        return os.path.dirname(os.path.join(self.folder, m["files"][0]["path"]))

    def _load(self, name, make):
        """The manifest entry `name` loaded by `make(its folder)`. Whatever stops it (a file missing or changed, a
        package that does not import, a library that cannot open the file) is the piece not loading: Unloadable."""
        try: return make(self._checked(name))
        except Unloadable: raise
        except Exception as e:
            m = next(m for m in manifest()["models"] if m["name"] == name)
            raise Unloadable(m.get("said") or name, e) from e

    def detector(self):
        if self._detector is None:
            def make(folder):
                from .detect import Detector
                return Detector(os.path.join(folder, "detector_int8.onnx"), threads=self.threads)
            self._detector = self._load("detector", make)
        return self._detector

    def finder(self):
        if self._finder is None:
            def make(folder):
                from .lines import Finder
                return Finder(os.path.join(folder, "inference.onnx"), threads=self.threads)
            self._finder = self._load("lines", make)
        return self._finder

    def line_reader(self, system):
        """The line reader of a writing system (latin, cyrillic, greek, korean, chinese)."""
        key = "line:" + system
        if key not in self._readers:
            name = next((m["name"] for m in manifest()["models"] if m.get("reads") == system and m["name"] != "reader"), None)
            if name is None: raise ValueError("no line reader for " + system)
            def make(folder):
                from .reader import LineReader
                return LineReader(folder, threads=self.threads)
            self._readers[key] = self._load(name, make)
        return self._readers[key]

    def reader(self, language="ja"):
        """The reader of a language: a callable taking a block's crop and giving its text. A language no reader knows
        is the caller's error (ValueError); a reader that does not load is Unloadable."""
        from .reader import BlockReader, system_of
        system = system_of(language)
        if system not in self._readers:
            if system == "japanese":
                def make(folder):
                    from .reader import Reader
                    return Reader(folder, threads=self.threads)
                self._readers[system] = self._load("reader", make)
            else: self._readers[system] = BlockReader(self.finder(), self.line_reader(system), system)
        return self._readers[system]

def load(folder, threads=4, language="ja"):
    """(detector, reader) from a folder that `fetch` filled, for one language (Japanese when none is named)."""
    got = Loaded(folder, threads)
    return got.detector(), got.reader(language)
