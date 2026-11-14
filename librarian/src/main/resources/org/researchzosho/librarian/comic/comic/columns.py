"""Geometry of a block of vertical text, shared by the furigana step and the sideways-digits step.
From the lettering's ink alone (no reader): the main columns, the narrow runs of small glyphs standing to the
right of a main column (furigana), and the row bands of a main column (glyph rows), which is where a number
set sideways is looked for. Coordinates are page pixels.

ink      = dark pixels inside the balloon's filled inside where one was found (gray < 140; in a balloon that is not
           white, the pixels far from the balloon's own colour, see balloon.py); for a block with no balloon (free
           text over art or tone) the text box grown a little, thresholded by Otsu's method (its light side when the
           box is mostly dark). Specks smaller than 0.12 G are dropped.
G        = the block's main glyph size: the 85th percentile of the larger side of its ink parts.
column   = a run of the ink's column profile. In a run wider than 1.2 G the main column is the G-wide window with
           most ink; what is left beside it is a column of its own when an almost empty place parts the two.
main     = a column at least 0.62 G wide and at least 0.3 times as dense in ink as the block's densest wide column.
narrow   = a column 0.28..0.62 G wide (by where most of its ink lies) whose left edge is within 0.6 G of a main column's right edge and whose
           parts are glyphs (median part height at least 0.22 G; smaller parts are emphasis dots, left out).
run      = a stretch of such a column with no vertical gap over 0.55 G, judged on its own width."""
import cv2
import numpy as np
from .balloon import interior, is_white, near_and_dark

def ink_of(gray, block, bubbles, rgb=None):
    """(ink mask over a region, region origin (x0, y0), how). rgb: the page in colour (HxWx3); with it a balloon that
    is not white gives its ink as the pixels far from its own colour, and a block outside any balloon whose box is
    mostly dark gives its light pixels (light lettering on dark art)."""
    tb = block["box"]; H, W = gray.shape
    got = interior(gray, tb, bubbles, rgb) if block.get("cls") != "text_free" else None
    mx, my = 0.10 * (tb[2] - tb[0]), 0.04 * (tb[3] - tb[1])
    bx0, by0, bx1, by1 = int(max(0, tb[0] - mx)), int(max(0, tb[1] - my)), int(min(W, tb[2] + mx)), int(min(H, tb[3] + my))
    if got is not None:
        x0, y0 = got["origin"]; f = got["filled"]
        if rgb is None or is_white(got["paper"]): ink = (gray[y0:y0 + f.shape[0], x0:x0 + f.shape[1]] < 140) & f
        else: ink = near_and_dark(None, rgb[y0:y0 + f.shape[0], x0:x0 + f.shape[1]], got["paper"])[1] & f
        keep = np.zeros_like(ink); keep[max(0, by0 - y0):by1 - y0, max(0, bx0 - x0):bx1 - x0] = True
        return ink & keep, (x0, y0), got["how"]
    reg = np.ascontiguousarray(gray[by0:by1, bx0:bx1])
    thr, _ = cv2.threshold(reg, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    if rgb is not None and (reg <= thr).mean() > 0.6: return reg > max(115, thr), (bx0, by0), "text box, Otsu, light on dark"
    return reg < min(140, thr), (bx0, by0), "text box, Otsu"

def spans(profile, join=2):
    out, s = [], None
    for i, v in enumerate(profile):
        if v and s is None: s = i
        if not v and s is not None:
            out.append([s, i]); s = None
    if s is not None: out.append([s, len(profile)])
    merged = []
    for a, b in out:
        if merged and a - merged[-1][1] < join: merged[-1][1] = b
        else: merged.append([a, b])
    return merged

def analyse(gray, block, bubbles, rgb=None):
    """dict with G, main columns (each with its glyph rows) and furigana runs; None when not vertical text."""
    ink, (ox, oy), how = ink_of(gray, block, bubbles, rgb)
    if ink.sum() < 30: return None
    n, lab, st, _ = cv2.connectedComponentsWithStats(ink.astype(np.uint8), connectivity=8)
    if n < 2: return None
    big = np.maximum(st[1:, cv2.CC_STAT_WIDTH], st[1:, cv2.CC_STAT_HEIGHT])
    big = big[st[1:, cv2.CC_STAT_AREA] >= 6]
    if len(big) == 0: return None
    G = float(np.percentile(big, 85))
    tb = block["box"]
    if G < 8 or (tb[3] - tb[1]) < 1.6 * G or G > 0.9 * (tb[2] - tb[0]) + 6: return None
    small = [i for i in range(1, n) if max(st[i, cv2.CC_STAT_WIDTH], st[i, cv2.CC_STAT_HEIGHT]) < 0.12 * G]
    if small: ink = ink & ~np.isin(lab, small)
    dens = ink.sum(axis=0).astype(float)
    cols = []
    for a, b in spans(dens >= 1):
        if b - a <= 1.2 * G:
            cols.append([a, b]); continue
        # a wide run: one main column with small glyphs touching it on the right, on the left, or both.
        # The main column is the G-wide window that holds most ink; what is left on a side is a column of its own
        # only when an almost empty place (under a fifth of the main part's median ink) parts it from the main part.
        wn = int(round(G)); cs = np.concatenate([[0.0], np.cumsum(dens[a:b])])
        k = int(np.argmax(cs[wn:] - cs[:-wn])) if b - a > wn else 0
        m0, m1 = a + k, min(b, a + k + wn)
        med = float(np.median(dens[m0:m1][dens[m0:m1] > 0])) if (dens[m0:m1] > 0).any() else 1.0
        parts = []
        if m0 - a >= 0.28 * G:
            lo = max(a + 1, m0 - int(0.25 * G)); hi = min(b - 1, m0 + int(0.2 * G)); cut = lo + int(np.argmin(dens[lo:hi + 1]))
            if dens[cut] <= 0.2 * med: parts.append([a, cut]); m0 = cut
        if b - m1 >= 0.28 * G:
            lo = max(a + 1, m1 - int(0.2 * G)); hi = min(b - 1, m1 + int(0.25 * G)); cut = lo + int(np.argmin(dens[lo:hi + 1]))
            if dens[cut] <= 0.2 * med: parts.append([cut, b]); m1 = cut
        else: m1 = b
        if not any(q[1] <= m0 for q in parts): m0 = a
        if not any(q[0] >= m1 for q in parts): m1 = b
        cols.append([m0, m1]); cols.extend(parts)
    info = []
    for a, b in sorted(cols):
        sub = ink[:, a:b]; ys = np.nonzero(sub.sum(axis=1))[0]
        if not len(ys): continue
        xs = np.nonzero(sub)[1]; lo, hi = np.percentile(xs, [4, 96])      # the width most of the ink keeps to
        info.append({"x": [a, b], "y": [int(ys[0]), int(ys[-1]) + 1], "w": float(hi - lo + 1), "area": int(sub.sum())})
    for c in info: c["lin"] = c["area"] / float(max(1, c["y"][1] - c["y"][0]))      # ink per pixel of height
    top = max((c["lin"] for c in info if c["w"] >= 0.62 * G), default=0.0)
    mains = [c for c in info if c["w"] >= 0.62 * G and c["lin"] >= 0.3 * top]        # wide AND as dense as lettering
    if not mains: return None
    out = {"G": G, "origin": (ox, oy), "how": how, "mains": [], "furigana": []}
    for m in mains:
        rows = [[oy + a, oy + b] for a, b in spans(ink[:, m["x"][0]:m["x"][1]].sum(axis=1) >= 1, join=2)]
        out["mains"].append({"x": [ox + m["x"][0], ox + m["x"][1]], "y": [oy + m["y"][0], oy + m["y"][1]], "rows": rows})
    # small glyphs: the ink parts outside the main columns, chained from the top while they overlap sideways
    # (so a tone fragment beside a reading does not join it) and follow within 0.55 G
    n2, lab2, st2, _ = cv2.connectedComponentsWithStats(ink.astype(np.uint8), connectivity=8)
    parts = []
    for i in range(1, n2):
        x, y, w, h, area = (int(v) for v in st2[i])
        if max(w, h) < 0.16 * G or max(w, h) > 0.75 * G: continue
        cx = x + w / 2.0
        if any(m["x"][0] <= cx < m["x"][1] for m in mains): continue
        parts.append([x, y, x + w, y + h, area])
    parts.sort(key=lambda q: q[1]); chains = []
    for q in parts:
        for ch in chains:
            l = ch[-1]
            ov = min(q[2], max(t[2] for t in ch)) - max(q[0], min(t[0] for t in ch))
            if q[1] - max(t[3] for t in ch) <= 0.55 * G and ov >= 0.4 * min(q[2] - q[0], l[2] - l[0]):
                ch.append(q); break
        else:
            chains.append([q])
    for ch in chains:
        x0, y0, x1, y1 = min(t[0] for t in ch), min(t[1] for t in ch), max(t[2] for t in ch), max(t[3] for t in ch)
        w = x1 - x0
        if not (0.28 * G <= w <= 0.62 * G) or y1 - y0 < 0.6 * w: continue
        left = [m for m in mains if m["x"][1] <= x0 + 2 and x0 - m["x"][1] <= 0.6 * G]
        if not left: continue
        m = max(left, key=lambda m: m["x"][1])
        if y1 <= m["y"][0] - 0.3 * G or y0 >= m["y"][1] + 0.3 * G: continue              # not beside the column
        hs = [t[3] - t[1] for t in ch]
        if np.median(hs) < 0.22 * G and max(hs) < 0.3 * G: continue                        # emphasis dots
        fill = sum(t[4] for t in ch) / float(w * (y1 - y0))
        if not (0.06 <= fill <= 0.75): continue
        out["furigana"].append({"x": [ox + x0, ox + x1], "y": [oy + y0, oy + y1],
                                "main_x": [ox + m["x"][0], ox + m["x"][1]], "main_y": [oy + m["y"][0], oy + m["y"][1]]})
        for t in ch: t.append("used")
    # a reading set one kana at a time along a long word: single parts standing in the band of the pieces found
    # beside the same main column belong to it as well
    for m in mains:
        mine = [f for f in out["furigana"] if f["main_x"] == [ox + m["x"][0], ox + m["x"][1]]]
        if not mine: continue
        bx0, bx1 = min(f["x"][0] for f in mine) - ox, max(f["x"][1] for f in mine) - ox
        for q in parts:
            if len(q) > 5: continue
            cx = (q[0] + q[2]) / 2.0
            if bx0 - 1 <= cx <= bx1 + 1 and m["y"][0] - 0.3 * G <= q[1] and q[3] <= m["y"][1] + 0.3 * G and max(q[2] - q[0], q[3] - q[1]) >= 0.2 * G:
                q.append("used")
                out["furigana"].append({"x": [ox + q[0], ox + q[2]], "y": [oy + q[1], oy + q[3]],
                                        "main_x": [ox + m["x"][0], ox + m["x"][1]], "main_y": [oy + m["y"][0], oy + m["y"][1]]})
    return out

PITCH = 1.13          # a column's cell height over the glyph size G (the lettering's leading)

def place_in_text(a, main_x, y1, y2):
    """Where a stretch of a main column stands in the block's reading order, in CELLS from the block's first
    character: columns right to left, each from its top, a cell being PITCH x G high. It does not use the length
    of the reader's text, which a misreading changes."""
    mains = sorted(a["mains"], key=lambda m: -m["x"][0]); p = PITCH * a["G"]; before = 0.0
    for m in mains:
        if m["x"] == main_x:
            return before + (y1 - m["y"][0]) / p, before + (y2 - m["y"][0]) / p
        before += max(1, round((m["y"][1] - m["y"][0]) / p))
    return None
