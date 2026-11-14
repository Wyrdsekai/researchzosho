"""A number set sideways in a vertical column (two to four narrow digits in one cell), which the reader tends to
shorten. For a block whose text holds digits: every glyph row of its main columns that is 0.3..1.1 G high and stands
within 2.5 cells of where a run of digits stands in the text is cut (the column's width and 0.3 G on each side),
enlarged 4 times and read by the same reader. A row counts as a sideways number only when the answer is 2 to 4
digits and nothing else. It replaces the nearest run of digits in the text, one for one; nothing else is touched.
Japanese only: read.py runs this for no other language (their readers read digits as they stand in the line)."""
import re
import unicodedata
import numpy as np
from PIL import Image
from .columns import place_in_text
from .furigana import units_of

def nfkc(s): return unicodedata.normalize("NFKC", s or "")

def enlarge(gray, x1, y1, x2, y2, scale=4, pad=14):
    H, W = gray.shape
    c = Image.fromarray(np.ascontiguousarray(gray[max(0, y1 - 2):min(H, y2 + 2), max(0, x1):min(W, x2)]))
    c = c.resize((c.width * scale, c.height * scale), Image.LANCZOS)
    out = Image.new("L", (c.width + 2 * pad, c.height + 2 * pad), 255); out.paste(c, (pad, pad))
    return out.convert("RGB")

def digit_runs(text):
    """Runs of digits in the text as (first unit, last unit + 1, the digits)."""
    units = units_of(text); runs = []; k = 0
    while k < len(units):
        if nfkc(text[units[k][0]:units[k][1]]).isdigit():
            j = k
            while j < len(units) and nfkc(text[units[j][0]:units[j][1]]).isdigit(): j += 1
            runs.append((k, j, nfkc(text[units[k][0]:units[j - 1][1]]))); k = j
        else: k += 1
    return units, runs

def fix(gray, a, text, reader):
    """(the text with sideways numbers re-read, [{before, after}])."""
    units, runs = digit_runs(text)
    if a is None or not runs: return text, []
    G = a["G"]; cells = []
    for m in a["mains"]:
        for y1, y2 in m["rows"]:
            if not (0.3 * G <= y2 - y1 <= 1.1 * G): continue
            pos = place_in_text(a, m["x"], y1, y2)
            if pos is None: continue
            mid = (pos[0] + pos[1]) / 2
            if min(abs(mid - (r[0] + r[1]) / 2) for r in runs) > 2.5: continue
            t = re.sub(r"\s", "", nfkc(reader(enlarge(gray, int(m["x"][0] - 0.3 * G), y1, int(m["x"][1] + 0.3 * G), y2))))
            if re.fullmatch(r"\d{2,4}", t): cells.append((mid, t))
    pairs = sorted(((abs(pos - (r[0] + r[1]) / 2), pos, dg, r) for pos, dg in cells for r in runs), key=lambda t: t[0])
    used_r, used_c, todo = set(), set(), []
    for d, pos, dg, r in pairs:
        if d > 2.5 or r in used_r or pos in used_c: continue
        used_r.add(r); used_c.add(pos)
        if dg != r[2]: todo.append((r, dg))
    changes = []
    for r, dg in sorted(todo, key=lambda t: -t[0][0]):
        s, e = units[r[0]][0], units[r[1] - 1][1]
        wide = any(ord(c) > 0xFF00 for c in text[s:e])
        new = "".join(chr(ord(c) + 0xFEE0) for c in dg) if wide else dg
        changes.append({"before": text[s:e], "after": new}); text = text[:s] + new + text[e:]
    return text, changes[::-1]
