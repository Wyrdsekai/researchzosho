"""Furigana: the small kana printed to the right of a word in a vertical column.
Found by geometry (columns.py), placed by the column's own pitch, attached to the kanji it stands beside in the
reader's text, cut as one strip with its empty stretches closed up, and read by the same reader at two sizes.
A reading is kept when one size reads the word as a dictionary does (when one is installed; it also settles small
against large kana, since furigana is printed with small kana at full size), or when both sizes give kana and agree;
a reading the dictionary does not know is marked. A reading never goes into the block's main text.
Japanese only: read.py runs this for no other language, and only in a balloon whose paper is light."""
import re
import numpy as np
from PIL import Image
from .columns import analyse, place_in_text

KANJI = re.compile(r"[一-鿿々〆ヶ]")
KANA_ONLY = re.compile(r"[ぁ-ゖァ-ヺー]+")
BIG = str.maketrans("ぁぃぅぇぉっゃゅょゎ", "あいうえおつやゆよわ")

def hira(s): return "".join(chr(ord(c) - 0x60) if "ァ" <= c <= "ヶ" else c for c in s)
def clean(raw):
    t = re.sub(r"[\s．。、,.・…!！?？:：]", "", raw or "")
    return t if KANA_ONLY.fullmatch(t) else None

def units_of(text):
    """The text as cells of a vertical column: (start, end) character spans; a pair of ！？ marks and the three dots
    the reader writes for … stand in one cell."""
    out, i = [], 0
    while i < len(text):
        if text[i].isspace(): i += 1; continue
        m = re.match(r"[！？!?]{2}|．．．|\.\.\.|…", text[i:])
        j = i + (len(m.group()) if m else 1)
        out.append((i, j)); i = j
    return out

def kanji_runs(text, units):
    runs, s = [], None
    for k, (a, b) in enumerate(units):
        is_k = bool(KANJI.fullmatch(text[a:b]))
        if is_k and s is None: s = k
        if not is_k and s is not None: runs.append((s, k)); s = None
    if s is not None: runs.append((s, len(units)))
    return runs

def strip_image(gray, pieces, scale=4, pad=12):
    """One strip for a group of runs: per column of the group every row of ink between its top and bottom, stacked
    with the empty stretches closed up to a sixth of the width, enlarged."""
    H, W = gray.shape; cols = {}
    for x1, y1, x2, y2 in pieces:
        c = cols.setdefault(round((x1 + x2) / 2 / 6), [x1, y1, x2, y2])
        c[0], c[1], c[2], c[3] = min(c[0], x1), min(c[1], y1), max(c[2], x2), max(c[3], y2)
    crops = []
    for x1, y1, x2, y2 in sorted(cols.values(), key=lambda c: (-c[0], c[1])):
        x1, x2, y1, y2 = max(0, x1 - 2), min(W, x2 + 2), max(0, y1 - 1), min(H, y2 + 1)
        reg = gray[y1:y2, x1:x2]; rows = (reg < 140).sum(axis=1) >= 1; a = None
        for y in range(len(rows) + 1):
            on = y < len(rows) and rows[y]
            if on and a is None: a = y
            if not on and a is not None:
                crops.append(Image.fromarray(np.ascontiguousarray(reg[max(0, a - 1):min(len(rows), y + 1)]))); a = None
    if not crops: return None
    w = max(c.width for c in crops); gap = max(2, w // 6)
    col = Image.new("L", (w, sum(c.height for c in crops) + gap * (len(crops) - 1)), 255); y = 0
    for c in crops:
        col.paste(c, ((w - c.width) // 2, y)); y += c.height + gap
    col = col.resize((col.width * scale, col.height * scale), Image.LANCZOS)
    out = Image.new("L", (col.width + 2 * pad, col.height + 2 * pad), 255); out.paste(col, (pad, pad))
    return out.convert("RGB")

_tagger = False
def dictionary_reading(base):
    """The base's reading by a dictionary (fugashi with a UniDic), in hiragana; None when there is no dictionary or
    it gives none."""
    global _tagger
    if _tagger is False:
        try:
            import fugashi; _tagger = fugashi.Tagger()
        except Exception: _tagger = None
    if _tagger is None: return None
    try: kana = "".join((w.feature.kana or "") for w in _tagger(base))
    except Exception: return None
    return hira(kana) if kana and KANA_ONLY.fullmatch(kana) else None

def find(a, text):
    """[{base_units, strips, runs}] for one block from its column analysis `a`, in reading order."""
    if a is None or not a["furigana"]: return []
    units = units_of(text); runs = kanji_runs(text, units)
    if not runs: return []
    G = a["G"]; att = {}
    for f in a["furigana"]:
        pl = place_in_text(a, f["main_x"], f["y"][0], f["y"][1])
        if pl is None: continue
        u1, u2 = pl; best, score = None, -1e9
        for r in runs:
            ov = min(u2, r[1]) - max(u1, r[0])
            if ov > score: best, score = r, ov
        if best is None or score < -1.5: continue                  # no kanji near it: not attached
        att.setdefault(best, []).append((f, u1, u2))
    out = []
    for r in sorted(att):
        fs = sorted(att[r], key=lambda t: (-t[0]["main_x"][0], t[0]["y"][0])); groups = [[fs[0]]]
        for t in fs[1:]:
            p = groups[-1][-1][0]
            if t[0]["main_x"] != p["main_x"] or t[0]["y"][0] - p["y"][1] <= 1.3 * G: groups[-1].append(t)
            else: groups.append([t])
        for g in groups:
            u1, u2 = min(t[1] for t in g), max(t[2] for t in g)
            k = int(min(r[1] - r[0], max(1, round(u2 - u1 + 0.3))))
            first = max(range(r[0], r[1] - k + 1), key=lambda s0: min(u2, s0 + k) - max(u1, s0))
            out.append({"base_units": (first, first + k), "strips": [[t[0]["x"][0], t[0]["y"][0], t[0]["x"][1], t[0]["y"][1]] for t in g], "runs": len(g)})
    return out

def read(gray, a, text, reader):
    """The readings of one block: [{base, start, end, reading, note}] with character positions in `text`,
    and the strips that were dropped as [{base, why}]."""
    kept, dropped = [], []
    units = units_of(text)
    for it in find(a, text):
        s, e = units[it["base_units"][0]][0], units[it["base_units"][1] - 1][1]; base = text[s:e]
        im4 = strip_image(gray, it["strips"], scale=4); im6 = strip_image(gray, it["strips"], scale=6)
        if im4 is None: continue
        r4, r6 = clean(reader(im4)), clean(reader(im6))
        d = dictionary_reading(base); same = lambda x, y: hira(x).translate(BIG) == hira(y).translate(BIG)
        by_dict = next((r for r in (r4, r6) if r and d and same(r, d)), None)
        if by_dict:                                           # one size reads it as the dictionary does: that settles it
            rd = by_dict if hira(by_dict) == d else d
            note = "as the dictionary reads the word" if hira(by_dict) == d else "small kana as the dictionary has them"
        elif r4 and r6 and same(r4, r6):
            rd, note = r4, ("the dictionary reads the word otherwise (" + d + ")" if d else None)
        else:
            dropped.append({"base": base, "why": "not kana, or the two readings of the strip differ and no dictionary settles it"}); continue
        kept.append({"base": base, "start": s, "end": e, "reading": rd, "note": note})
    return kept, dropped
