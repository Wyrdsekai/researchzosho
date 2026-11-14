"""The long-lived mode: one process loads the models once and answers requests, one JSON object per line on stdin,
one per line on stdout. First line out: {"ready": true, ...}. A request that fails is answered with an error object
{"ok": false, "error": {"type": ..., "message": ...}} and the process lives on. The type "Unloadable" is a piece of the
helper that does not load on this machine (models.Unloadable: a reader's file missing or changed, a package that does
not import): no page's failure, every page would get it. Nothing but these lines is written to stdout.
  {"id": 1, "op": "read", "images": ["a.jpg", "folder"], "language": "ko", "order": "ltr", "double_read": false}
                                                                                        -> {"id": 1, "ok": true, "pages": [...]}
      language: a code, absent means ja; its reader is loaded at its first use and kept. order: "rtl" or "ltr", absent
      means the language's own (read.py). A language no reader knows is an error object naming it.
  {"id": 5, "op": "script", "images": ["p003.jpg", "p007.jpg"]}
      -> {"id": 5, "ok": true, "script": "japanese"|"korean"|"chinese"|"latin"|"cyrillic"|"greek"|"none", "vertical": false,
          "share": 0.93, "texts": ["text of a block", ...], "letters": {...}}                              (script.py)
  {"id": 6, "op": "cut", "image": "ep01.jpg", "out": "work/ep01"}
      -> {"id": 6, "ok": true, "strip": true, "width": 800, "height": 12000, "pieces": [{"file": "work/ep01/ep01_001.png", "top": 0, "height": 1180}, ...],
          "cut_through": ["gutter", ...]}; an image that is no strip: "strip": false, no pieces, nothing written   (strip.py)
  {"id": 7, "op": "join", "pieces": [{"file": "work/ep01/ep01_001_set.png", "top": 0}, ...], "width": 800, "height": 12000, "out": "out/ep01.jpg"}
      -> {"id": 7, "ok": true, "out": "out/ep01.jpg"}
  {"id": 8, "op": "crops", "image": "p003.jpg", "page": {...one page of a read reply...}, "ids": ["b01", "b04"], "out": "work/p003-crops"}
      -> {"id": 8, "ok": true, "crops": [{"id": "b01", "file": "work/p003-crops/b01.png", "size": [w, h]}, ...]}
      ids absent: every speech block of the page. A block's reading crop at the size a model that sees is shown it (crops.py).
  {"id": 2, "op": "set", "image": "a.jpg", "page": {...}, "texts": {"b01": {"text": "...", "lang": "en"}},
   "font": "F.ttf", "more_fonts": ["G.ttf"], "hyphenation": "dir", "out": "a_set.jpg", "caps": true}
                                                                                        -> {"id": 2, "ok": true, "out": "a_set.jpg", "report": {...}}
  {"id": 3, "op": "picture", "image": "a.jpg", "boxes": [["1", [x1, y1, x2, y2]]], "long_side": 1300, "out": "a_boxes.jpg"}
                                                                                        -> {"id": 3, "ok": true, "out": "a_boxes.jpg", "size": [w, h]}
  {"op": "quit"}                                                                        -> {"ok": true, "bye": true}
"font" is the font a block is set in; a block with a letter it lacks is set in the first of "more_fonts" that has it.
A text of set may carry "vertical": true ({"text": "…", "lang": "zh", "vertical": true}): a Chinese or Japanese block is
then set in columns; absent or false, and for every other language, in lines (setter.py).
A set request may carry "keep": ["齐藤英二郎", "Eiroku", ...]: strings no line or column may end inside, in the language
being set (the comic's names and terms). A block they would make too small is set without them and its report names the
ones a line ends inside under "keep_broken". The Chinese word list (the manifest's words/ folder) is read from the
models folder the process was started with."""
import json, os, sys, time
from PIL import Image
from . import __version__, models
from .picture import numbered
from . import strip
from .read import read_files, images_of
from .reader import languages
from .script import script_of
from .crops import crops as block_crops
from .setter import Setter

def serve(models_dir, threads=4, font=None, hyphenation=None, more_fonts=()):
    out = sys.stdout; sys.stdout = sys.stderr                    # anything a library prints goes to stderr
    def say(obj):
        out.write(json.dumps(obj, ensure_ascii=False) + "\n"); out.flush()
    t = time.time(); loaded = models.Loaded(models_dir, threads=threads); detector = loaded.detector()      # a reader is loaded when a language first asks for it
    say({"ready": True, "version": __version__, "models": models.versions(), "threads": threads, "load_s": round(time.time() - t, 1), "languages": languages()})
    setters = {}
    for line in sys.stdin:
        line = line.strip()
        if not line: continue
        rid = None
        try:
            req = json.loads(line)
            if not isinstance(req, dict): raise ValueError("a request is one JSON object")
            rid = req.get("id"); op = req.get("op")
            if op == "quit":
                say({"id": rid, "ok": True, "bye": True}); break
            if op == "read":
                t = time.time(); language = req.get("language") or "ja"
                res = read_files(req["images"], detector, loaded.reader(language), double_read=bool(req.get("double_read")), language=language, order=req.get("order"))
                say({"id": rid, "ok": True, "pages": res["pages"], "sec": round(time.time() - t, 2)})
            elif op == "script":
                t = time.time(); res = script_of(images_of(req["images"]), loaded)
                say(dict({"id": rid, "ok": True}, **res, sec=round(time.time() - t, 2)))
            elif op == "cut":
                say(dict({"id": rid, "ok": True}, **strip.cut(req["image"], req["out"], detector)))
            elif op == "join":
                say({"id": rid, "ok": True, "out": strip.join(req["pieces"], req["width"], req["height"], req["out"])})
            elif op == "crops":
                say({"id": rid, "ok": True, "crops": block_crops(req["image"], req["page"], req["out"], req.get("ids"))})
            elif op == "set":
                f = req.get("font") or font
                if not f: raise ValueError("set needs a font: give \"font\" in the request or --font at start")
                key = (f, req.get("hyphenation") or hyphenation, bool(req.get("caps", True)), tuple(req.get("more_fonts") or more_fonts))
                if key not in setters: setters[key] = Setter(key[0], key[1], caps=key[2], more_fonts=key[3], words=os.path.join(models_dir, "words"))
                with Image.open(req["image"]) as im:
                    canvas, report = setters[key].set_page(im, req["page"], req["texts"], keep=req.get("keep") or ())
                canvas.save(req["out"], quality=90)
                say({"id": rid, "ok": True, "out": req["out"], "report": report})
            elif op == "picture":
                with Image.open(req["image"]) as im:
                    pic = numbered(im, [(b[0], b[1]) for b in req.get("boxes", [])], long_side=int(req.get("long_side", 1300)))
                pic.save(req["out"], format="JPEG", quality=88)
                say({"id": rid, "ok": True, "out": req["out"], "size": list(pic.size)})
            else:
                raise ValueError(f"unknown op {op!r}: read, script, cut, join, crops, set, picture or quit")
        except Exception as e:
            say({"id": rid, "ok": False, "error": {"type": type(e).__name__, "message": str(e)[:400]}})
