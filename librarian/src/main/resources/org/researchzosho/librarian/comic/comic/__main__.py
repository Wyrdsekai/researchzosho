"""python -m comic fetch | models | check | read | script | cut | join | crops | set | serve      (each module's docstring says what it does)"""
import argparse, json, os, sys

def main(argv=None):
    ap = argparse.ArgumentParser(prog="python -m comic", description="Read the lettering of comic pages and set text back into the balloons.")
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("fetch", help="download exactly the manifest's files into a folder"); p.add_argument("--to", required=True); p.add_argument("--user-agent", default="comic-fetch/0.1")
    p = sub.add_parser("models", help="print the manifest, and the state of a folder"); p.add_argument("--in", dest="folder")
    p = sub.add_parser("check", help="load every piece once (the packages, the models, the fonts, the patterns, the word list) and say which does not load")
    p.add_argument("--models", required=True); p.add_argument("--threads", type=int, default=4)
    p.add_argument("--import", dest="modules", action="append", default=[], help="a Python package the helper needs, by the name it is imported by (may be given more than once)")
    p = sub.add_parser("read", help="page images in, JSON out")
    p.add_argument("images", nargs="+", help="image files or folders"); p.add_argument("--models", required=True); p.add_argument("--threads", type=int, default=4)
    p.add_argument("--double-read", action="store_true", help="read every block a second time, enlarged, and mark blocks whose two readings differ")
    p.add_argument("--out", help="write the JSON here (default: stdout)")
    p.add_argument("--language", default="ja", help="the language the comic is lettered in, a code: ja (the default) en zh ko de fr es pt it ru ...")
    p.add_argument("--order", choices=["rtl", "ltr"], help="rows read right to left or left to right (default: the language's own)")
    p = sub.add_parser("script", help="say which writing system a few pages are lettered in, and print a sample of their text")
    p.add_argument("images", nargs="+", help="image files or folders"); p.add_argument("--models", required=True); p.add_argument("--threads", type=int, default=4)
    p = sub.add_parser("cut", help="cut a web comic's strip (one very tall image) into pieces of a page's shape")
    p.add_argument("image"); p.add_argument("--out", required=True, help="the folder the pieces are written to"); p.add_argument("--models", required=True); p.add_argument("--threads", type=int, default=4)
    p = sub.add_parser("join", help="join a strip's pieces again")
    p.add_argument("--pieces", required=True, help='JSON: the reply of cut, or its list of pieces [{"file", "top"}]'); p.add_argument("--width", type=int); p.add_argument("--height", type=int); p.add_argument("--out", required=True)
    p = sub.add_parser("crops", help="write a page's blocks as pictures for a model that sees (the reading crops, made large)")
    p.add_argument("--image", required=True); p.add_argument("--page", required=True, help="JSON from read: one page, or the whole output"); p.add_argument("--out", required=True, help="the folder the crops are written to")
    p.add_argument("--id", action="append", help="a block's id (may be given more than once; default: every speech block)")
    p = sub.add_parser("set", help="set text into a page's balloons")
    p.add_argument("--image", required=True); p.add_argument("--page", required=True, help="JSON from read: one page, or the whole output")
    p.add_argument("--texts", required=True, help='JSON: {"b01": {"text": "...", "lang": "en"}, "b02": {"text": "…", "lang": "zh", "vertical": true}, ...}; vertical sets a Chinese or Japanese block in columns'); p.add_argument("--font", required=True)
    p.add_argument("--hyphenation", help="folder of hyph-<language>.pat.txt files"); p.add_argument("--out", required=True); p.add_argument("--report")
    p.add_argument("--no-caps", action="store_true"); p.add_argument("--more-font", action="append", default=[], help="a font for a block with a letter --font lacks (may be given more than once)")
    p.add_argument("--keep", action="append", default=[], help="a name or a term no line may end inside (may be given more than once)"); p.add_argument("--words", help="the folder of the Chinese word list (the models folder's words/)")
    p = sub.add_parser("serve", help="load the models once and answer JSON lines on stdin/stdout")
    p.add_argument("--models", required=True); p.add_argument("--threads", type=int, default=4); p.add_argument("--font"); p.add_argument("--hyphenation")
    p.add_argument("--more-font", action="append", default=[])
    a = ap.parse_args(argv)
    from . import models
    if a.cmd == "fetch":
        models.fetch(a.to, user_agent=a.user_agent); return 0
    if a.cmd == "models":
        print(json.dumps(models.manifest(), indent=1))
        if a.folder: [print(s, p) for p, s in models.check(a.folder)]
        return 0
    if a.cmd == "check":
        from .check import check
        return check(a.models, threads=a.threads, modules=a.modules)
    if a.cmd == "read":
        from .read import read_files
        detector, reader = models.load(a.models, threads=a.threads, language=a.language)
        res = read_files(a.images, detector, reader, double_read=a.double_read, language=a.language, order=a.order)
        s = json.dumps(res, ensure_ascii=False, indent=1)
        if a.out:
            with open(a.out, "w", encoding="utf-8") as f: f.write(s)
        else: sys.stdout.write(s + "\n")
        return 0
    if a.cmd == "script":
        from .read import images_of
        from .script import script_of
        print(json.dumps(script_of(images_of(a.images), models.Loaded(a.models, threads=a.threads)), ensure_ascii=False, indent=1)); return 0
    if a.cmd == "cut":
        from . import strip
        print(json.dumps(strip.cut(a.image, a.out, models.Loaded(a.models, threads=a.threads).detector()), ensure_ascii=False, indent=1)); return 0
    if a.cmd == "join":
        from . import strip
        with open(a.pieces, encoding="utf-8") as f: got = json.load(f)
        pieces = got["pieces"] if isinstance(got, dict) else got
        w = a.width or (got.get("width") if isinstance(got, dict) else None); h = a.height or (got.get("height") if isinstance(got, dict) else None)
        if not w or not h: ap.error("join needs --width and --height when --pieces is a bare list")
        print(strip.join(pieces, w, h, a.out)); return 0
    if a.cmd == "crops":
        from .crops import crops
        with open(a.page, encoding="utf-8") as f: page = json.load(f)
        if "pages" in page:
            nm = os.path.basename(a.image); page = next((p for p in page["pages"] if p.get("image") == nm), page["pages"][0])
        print(json.dumps(crops(a.image, page, a.out, a.id), ensure_ascii=False, indent=1)); return 0
    if a.cmd == "set":
        from PIL import Image
        from .setter import Setter
        with open(a.page, encoding="utf-8") as f: page = json.load(f)
        if "pages" in page:
            nm = os.path.basename(a.image); page = next((p for p in page["pages"] if p.get("image") == nm), page["pages"][0])
        with open(a.texts, encoding="utf-8") as f: texts = json.load(f)
        with Image.open(a.image) as im:
            canvas, report = Setter(a.font, a.hyphenation, caps=not a.no_caps, more_fonts=a.more_font, words=a.words).set_page(im, page, texts, keep=a.keep)
        canvas.save(a.out, quality=90)
        if a.report:
            with open(a.report, "w", encoding="utf-8") as f: json.dump(report, f, ensure_ascii=False, indent=1)
        else: print(json.dumps(report, ensure_ascii=False))
        return 0
    if a.cmd == "serve":
        from .serve import serve
        serve(a.models, threads=a.threads, font=a.font, hyphenation=a.hyphenation, more_fonts=a.more_font); return 0

if __name__ == "__main__":
    sys.exit(main())
