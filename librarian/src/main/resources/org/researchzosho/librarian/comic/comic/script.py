"""script: which writing system is a comic lettered in? Given a few page images it reads a sample of their blocks with
every line reader and says what the letters are. Kana means Japanese, hangul Korean, han alone Chinese; Latin, Cyrillic
and Greek by their letters. It returns the sample's text as well, so that the program can tell French from English by
its own means. It loads no Japanese reader (manga-ocr): the line readers are enough to see kana.

How: the page detector finds the blocks; of each page the PER_PAGE (5) blocks in balloons with the best scores are taken
(blocks outside balloons only when a page has no balloon block), SAMPLE (40) at most over all pages. A block is cut into
lines or columns (lines.Finder) and every line is read by the five line readers (chinese, which also knows kana and
Latin; korean; latin; cyrillic; greek). A block's reading is the reader's whose letters it is most sure of (the mean
confidence over the block's lines); its letters are counted by writing system. The script is the system with most
letters, a han, a kana and a hangul syllable counting as WIDE (2) letters each (they take the room of two, and a comic
about computers in Chinese holds more Latin letters than han), kana and han counting together: Japanese when kana are
at least KANA (0.3) of them, else Chinese. Measured on 31 groups of three pages: Japanese pages 0.52 to 0.80 kana,
Chinese pages 0 (0.07 and 0.18 where a cover or drawn Japanese lettering was among the pages: give it story pages).
"none": fewer than LEAST (8) letters were read on all the pages.
  share     the share of the read letters, so weighted, that are in the script (kana and han both count for Japanese)
  vertical  more than half of the sampled blocks are lettered in columns
  texts     the sampled blocks as the script's own reader read them, in the order of the pages (Cyrillic and Greek
            with the Latin look-alike letters put right, as `read` gives them; the count of letters is taken before
            that, or an English page read by the Cyrillic reader would count as Cyrillic)"""
import unicodedata
from PIL import Image
from . import lines as L
from .geometry import crop_box
from .reader import JOIN, own_letters

SYSTEMS = ("chinese", "korean", "latin", "cyrillic", "greek")
PER_PAGE, SAMPLE, KANA, WIDE, LEAST = 5, 40, 0.3, 2, 8

def kind_of(c):
    o = ord(c)
    if 0x3041 <= o <= 0x3096 or 0x30A1 <= o <= 0x30FA: return "kana"
    if 0x3400 <= o <= 0x4DBF or 0x4E00 <= o <= 0x9FFF or 0xF900 <= o <= 0xFAFF: return "han"
    if 0xAC00 <= o <= 0xD7A3 or 0x3131 <= o <= 0x318E: return "hangul"
    if not c.isalpha(): return None
    name = unicodedata.name(c, "")
    if name.startswith("CYRILLIC"): return "cyrillic"
    if name.startswith("GREEK"): return "greek"
    if name.startswith("LATIN"): return "latin"
    return None

def script_of(paths, loaded):
    """The reply of the `script` request (without id and ok). loaded: models.Loaded."""
    detector, finder = loaded.detector(), loaded.finder(); readers = {s: loaded.line_reader(s) for s in SYSTEMS}
    counts = {k: 0 for k in ("kana", "han", "hangul", "latin", "cyrillic", "greek")}; sample = []; n_vertical = 0
    for p in paths:
        if len(sample) >= SAMPLE: break
        with Image.open(p) as im:
            img = im.convert("RGB")
        dets, _ = detector(img)
        pick = [d for d in dets if d["cls"] == "text_bubble"] or dets
        for d in sorted(pick, key=lambda d: -d["score"])[:PER_PAGE]:
            if len(sample) >= SAMPLE: break
            crop = img.crop(crop_box(d["box"], img.width, img.height)); boxes = finder(crop)
            cols = L.columns(boxes); order = cols or L.rows(boxes)
            cuts = [L.turned(crop, b) if cols else L.cut(crop, b) for b in order]; cuts = [c for c in cuts if c.width >= 2 and c.height >= 2]
            if not cuts: continue
            got = {}
            for s, r in readers.items():
                res = r(cuts); ls = [" ".join(t.split()) for t, _ in res if t.strip()]
                n = sum(len(t) for t, _ in res)
                got[s] = (JOIN.get(s, " ").join(ls), sum(cf * len(t) for t, cf in res) / n if n else 0.0)
            best = max(SYSTEMS, key=lambda s: got[s][1])
            for c in got[best][0]:
                k = kind_of(c)
                if k: counts[k] += 1
            sample.append({s: got[s][0] for s in SYSTEMS}); n_vertical += bool(cols)
    total = sum(counts.values())
    if total < LEAST:
        return {"script": "none", "vertical": False, "share": 0.0, "texts": [], "letters": counts}
    cjk = counts["kana"] + counts["han"]
    by = {"korean": WIDE * counts["hangul"], "latin": counts["latin"], "cyrillic": counts["cyrillic"], "greek": counts["greek"], "cjk": WIDE * cjk}
    top = max(by, key=by.get)
    share = by[top] / float(sum(by.values()))
    if top == "cjk": top = "japanese" if counts["kana"] >= KANA * cjk else "chinese"
    reader = "chinese" if top == "japanese" else top
    return {"script": top, "vertical": n_vertical > len(sample) / 2.0, "share": round(share, 3),
            "texts": [own_letters(t[reader], reader) for t in sample if t[reader]], "letters": counts}
