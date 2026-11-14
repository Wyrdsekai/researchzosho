"""read: a page image in, its lettering out as a dict.
Per page: find text and balloons, read each block with the reader of the page's language, take the balloon's inside,
its own colour (paper) and the lettering's colour (ink) from the page, say whether the block is lettered in columns,
decide speech / text outside a balloon / sound word, put the blocks in reading order, and mark the blocks a person
should look at. No translation and no model server call.

language  a code (ja en zh ko de fr es pt it ru and the other Latin, Cyrillic and Greek ones; reader.system_of).
          Japanese is read by manga-ocr, a whole block at once; furigana and sideways numbers are read by geometry
          (Japanese only, and only in a balloon whose paper is light). Every other language is cut into lines and read
          line by line (reader.BlockReader); "readings" and "numbers" stay empty. A block the page detector found
          outside any balloon is still speech when it stands in a caption box (caption_box).
order     "rtl" or "ltr": rows from the top, and inside a row right to left or left to right. When it is not given:
          Japanese reads right to left; Chinese reads right to left when more than half of its blocks in balloons are
          lettered in columns (the traditional layout), else left to right; every other language left to right.

The page:   {"image", "width", "height", "language", "direction": "rtl"|"ltr", "vertical": most balloon blocks are in
             columns, "dialogue_glyph_px", "order": [ids], "blocks": [...]}
A block:    {"id": "b01", "box", "crop": the rectangle the reader was given, "score",
             "kind": "speech"|"outside"|"sound", "vertical": true|false,
             "balloon": {"found", "closed", "outline", "paper": [r, g, b], "ink": [r, g, b]} or null,
             "text", "readings", "numbers", "sound", "glyph_px", "cell_px", "look": [...]}
            and, for every language but Japanese, "lines": the text of each line (or column) as it was read, and
            "sure": the reader's own confidence in the block, 0 to 1 (the lowest of its lines'; reader.BlockReader).
balloon     a balloon's inside; or a caption box ("found": "caption box"); or, for lettering that stands on open
            flat ground, the free ground around it ("found": "open ground"; on_open_ground below).
cell_px     the lettering's cell, glyph and spacing, which `set` sizes the new text by. Japanese: from the box and the
            number of characters. Chinese and Korean: 1.15 times the median line height (column width). Alphabets:
            1.25 times the letters' em, the em being the median line height, or that over 0.72 when the block is all
            capitals (a line of capitals is as high as its capitals, 0.72 of the em in a lettering font)."""
import os
import re
import cv2
import numpy as np
from PIL import Image
from . import furigana, digits, soundwords
from .balloon import interior, outline, gray_of, near_and_dark, open_ground, paper_of, fill_holes
from .columns import analyse, ink_of
from .geometry import crop_box, reading_order, rtl_of
from .lines import in_columns, glyph_px
from .reader import system_of, JOIN

def line_crop(box, found, gray, rgb, width, height):
    """The crop a block in a balloon is cut into lines from. The page detector's text box is often a few pixels short
    of a line's last letters, which the line reader then loses. So each side of the box is moved outwards over the
    balloon's lettering it cuts through: sideways until half a glyph of clear paper follows (a letter's own gaps are
    narrower), up and down until the first clear row; never further than 0.15 of the box's width (height) and 4 px,
    and never out of the balloon's inside. Then 3 px of margin. The crop is never smaller than geometry.crop_box's
    (a text box may reach beyond the inside found for it, where two balloons are joined), which is also what a block
    outside a balloon keeps."""
    if found is None: return crop_box(box, width, height)
    ox, oy = found["origin"]; f = found["filled"]; h, w = f.shape
    dark = near_and_dark(gray[oy:oy + h, ox:ox + w], rgb[oy:oy + h, ox:ox + w], found["paper"])[1] & f
    x0, y0 = max(0, int(box[0]) - ox), max(0, int(box[1]) - oy); x1, y1 = min(w, int(np.ceil(box[2])) - ox), min(h, int(np.ceil(box[3])) - oy)
    if x1 - x0 < 2 or y1 - y0 < 2: return crop_box(box, width, height)
    g = glyph_px(dark[y0:y1, x0:x1]) or 12.0; gap = max(3, int(round(0.5 * g)))
    lx, ly = int(0.15 * (x1 - x0)) + 4, int(0.15 * (y1 - y0)) + 4
    def sideways(x, step):
        last, clear = x, 0
        for k in range(1, lx + 1):
            c = x + step * k
            if c < 0 or c >= w: break
            if dark[y0:y1, c].any(): last, clear = c, 0
            else:
                clear += 1
                if clear >= gap: break
        return last
    def updown(y, step):
        for k in range(1, ly + 1):
            r = y + step * k
            if r < 0 or r >= h or not dark[r, x0:x1].any(): return y + step * (k - 1)
        return y + step * ly
    x0, x1 = sideways(x0, -1), sideways(x1 - 1, 1) + 1
    y0, y1 = updown(y0, -1), updown(y1 - 1, 1) + 1
    c = crop_box(box, width, height)                               # the crop never loses a part of the text box: it only grows
    return [min(c[0], int(max(0, ox + x0 - 3))), min(c[1], int(max(0, oy + y0 - 3))), max(c[2], int(min(width, ox + x1 + 3))), max(c[3], int(min(height, oy + y1 + 3)))]

def caption_box(gray, box, bubbles, rgb):
    """The inside of a caption box around a block the detector found outside any balloon, or None. It is a box when
    the text stands on one colour (half of the text box or more is within 48 of its paper), that colour's area is
    closed (it does not run on over the page) and is at most 6 times the text box. Such a block is lettered like
    speech when what is read in it is a sentence (soundwords.dialogue); a word or two in a box is a sign, a label or a
    logo and stays outside, as do text on art and text on a panel's open ground. Not used for Japanese."""
    found = interior(gray, box, bubbles, rgb, hull=True)
    if found is None or not found["closed"]: return None
    reg = rgb[max(0, int(box[1])):int(np.ceil(box[3])), max(0, int(box[0])):int(np.ceil(box[2]))].reshape(-1, 3).astype(np.int16)
    if len(reg) == 0 or (np.abs(reg - np.array(found["paper"], dtype=np.int16)).max(axis=1) < 48).mean() < 0.5: return None
    if found["filled"].sum() > 6.0 * (box[2] - box[0]) * (box[3] - box[1]): return None
    found["how"] = "caption box"
    return found

def on_open_ground(blocks, feats, bubbles, gray, rgb, system, psw, pG):
    """Lettering that stands on open flat ground (a web comic's narration and dialogue between its panels) is speech:
    a block left outside every balloon and box becomes `speech` with the free flat ground around it as its inside
    (balloon.open_ground, found = "open ground") when
      - what is read in it is a sentence (soundwords.dialogue): a word or two on the ground is a sound, a sign or a
        label, and stays outside;
      - it is not drawn much larger (1.6 times the glyphs) or heavier (1.5 times the strokes) than the page's
        lettering: the page's dialogue in balloons, or, on a page with none, the middle one of these blocks;
      - it does stand on flat ground of one colour, clear of the other text boxes, the balloons and the art.
    A sound word drawn on flat colour is kept out three ways: as a sound pattern it is already `sound`; as a word or
    two it is no sentence; drawn large or heavy it fails the second rule. Not used for Japanese."""
    cand = [(b, f) for b, f in zip(blocks, feats) if b["kind"] == "outside" and soundwords.dialogue(b["text"], system)]
    if not cand: return
    rG = pG or float(np.median([f["G"] for _, f in cand if f["G"]] or [0])) or None
    rs = psw or float(np.median([f["sw"] for _, f in cand if f["sw"]] or [0])) or None
    for b, f in cand:
        if (rG and f["G"] and f["G"] >= 1.6 * rG) or (rs and f["sw"] and f["sw"] >= 1.5 * rs): continue
        keep_out = [o["box"] for o in blocks if o is not b] + list(bubbles)
        found = open_ground(gray, rgb, b["box"], keep_out, f["G"])
        if found is None: continue
        b["kind"] = "speech"; f["in_balloon"] = True
        b["balloon"] = {"found": found["how"], "closed": True, "outline": outline(found), "paper": found["paper"], "ink": found["ink"]}

def japanese_columns(by_ink, box):
    """Whether a Japanese block is lettered in columns: what the gaps in its ink say (lines.in_columns), and, where
    they say lines, only a block wider than tall by more than three tenths is in lines. Japanese comics are lettered
    in columns; the gaps mislead where a column ends in ！！ or is a run of dots (on 30 pages of a manga the ink said
    lines of 17 speech blocks, and 16 were columns taller than wide; the one in lines was a sign 331 px by 75)."""
    return bool(by_ink) or (box[2] - box[0]) <= 1.3 * (box[3] - box[1])

def own_lines(got, cut, box, others, sep):
    """A block's reading without the lines that are another block's. The crop a block is read from is grown over the
    lettering its box cuts through, and so can take in the last rows of a block standing close above or beside it,
    which is then read twice (a shout over its balloon, on a real page). A line belongs elsewhere when more of its
    box lies in another text box than in this block's own, and at least half of it. `got`: reader.block()'s dict;
    `cut`: the crop's rectangle on the page; `others`: the other text boxes. A block is never left with no line."""
    def share(l, b):
        x1, y1, x2, y2 = l["box"][0] + cut[0], l["box"][1] + cut[1], l["box"][2] + cut[0], l["box"][3] + cut[1]
        return max(0.0, min(x2, b[2]) - max(x1, b[0])) * max(0.0, min(y2, b[3]) - max(y1, b[1])) / max(1e-6, (x2 - x1) * (y2 - y1))
    keep = [l for l in got["lines"] if not any(share(l, o) > max(share(l, box), 0.5) for o in others)]
    if not keep or len(keep) == len(got["lines"]): return got
    return dict(got, lines=keep, text=sep.join(l["text"] for l in keep), sure=min(l["confidence"] for l in keep))

def over_balloons(box, bubbles, gray, rgb, others=()):
    """The parts of a text box, one for each balloon inside it lies over; [box] when it lies in one.
    The page detector sometimes gives one text box to two balloons that stand close (hand-lettered comic books: a
    question and its answer, an order and the reply). Read as one block, their lines are mixed where the balloons
    stand side by side, the translation is set in the first balloon and the second is left. Two ways to see it:
      - the detector's own balloon boxes: two or more of them each hold a fifth of the text box or more, no one of them
        lying in another by more than three tenths of the smaller; the parts are the text box within each, cut apart
        at the middle of what they share;
      - the paper: two or more separate areas of the paper's colour each hold 15 % or more of the box's paper; a part
        is the text box within that area with its holes filled.
    Three things keep one balloon one block (each seen on real pages, where yellowed paper and lettering that runs
    into its outline break a balloon's paper into pieces):
      - a part holds lettering (20 dark pixels or more: enclosed by its paper, for the second way) and is, each way,
        12 px or more and 1.2 times the box's lettering (lines.glyph_px) or more: a box that reaches out over a gutter
        or an empty balloon is not split, nor one a panel's line runs through (a manga whose balloons lie over the
        panels' borders: the line cuts a sliver of paper off, with strokes of the first letters on it);
      - a part half or more of whose dark pixels lie in another text box of the page (`others`) is that block's own
        lettering, which this box only reaches over: it is no part;
      - the parts together hold 85 % or more of the box's paper (that of such a neighbour's part aside): otherwise
        the box lies over more paper than the parts, lines would be lost on it, and the box stays whole."""
    x1, y1, x2, y2 = [float(v) for v in box]; H, W = gray.shape; area = max(1.0, (x2 - x1) * (y2 - y1))
    ar = lambda b: max(0.0, b[2] - b[0]) * max(0.0, b[3] - b[1])
    within = lambda b: [max(x1, b[0]), max(y1, b[1]), min(x2, b[2]), min(y2, b[3])]
    both = lambda a, b: ar([max(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3])])
    bx0, by0, bx1, by1 = max(0, int(round(x1))), max(0, int(round(y1))), min(W, int(round(x2))), min(H, int(round(y2)))
    ink = gray[by0:by1, bx0:bx1] < 128
    paper = near_and_dark(gray[by0:by1, bx0:bx1], None if rgb is None else rgb[by0:by1, bx0:bx1], None if rgb is None else paper_of(rgb, box))[0]
    def area_of(b):
        m = np.zeros(ink.shape, bool); m[max(0, int(b[1]) - by0):max(0, int(np.ceil(b[3])) - by0), max(0, int(b[0]) - bx0):max(0, int(np.ceil(b[2])) - bx0)] = True
        return m
    def whole(parts):
        """The parts that count, when they are two or more and lose no lettering; else None."""
        least = max(12.0, 1.2 * (glyph_px(ink) or 0.0))                        # a balloon holds a line of lettering: a strip narrower than the lettering is a sliver a panel's line cut off
        parts = [p for p in parts if p[2] - p[0] >= least and p[3] - p[1] >= least]
        theirs = np.zeros(ink.shape, bool); kept = []                          # the lettering of parts that are another block's own: not this box's to keep or to lose
        for p in parts:
            m = area_of(p) & ink
            if any(int((m & area_of(o)).sum()) >= 0.5 * max(1, int(m.sum())) for o in others): theirs |= area_of(p)
            else: kept.append(p)
        if len(kept) < 2: return None
        held = np.zeros(ink.shape, bool)
        for p in kept: held |= area_of(p)
        mine = paper & ~(theirs & ~held)
        return [[int(round(v)) for v in p] for p in kept] if int((mine & held).sum()) >= 0.85 * max(1, int(mine.sum())) else None
    hold = [b for b in bubbles if ar(within(b)) >= 0.2 * area]
    hold = [b for i, b in enumerate(hold) if not any(j != i and both(b, o) > 0.3 * min(ar(b), ar(o)) and (ar(o), j) > (ar(b), i) for j, o in enumerate(hold))]
    if len(hold) >= 2:
        parts = [within(b) for b in hold]
        for i in range(len(parts)):
            for j in range(i + 1, len(parts)):
                a, b = parts[i], parts[j]; sx, sy = min(a[2], b[2]) - max(a[0], b[0]), min(a[3], b[3]) - max(a[1], b[1])
                if sx <= 0 or sy <= 0: continue
                k, m = (0, (max(a[0], b[0]) + min(a[2], b[2])) / 2.0) if sx <= sy else (1, (max(a[1], b[1]) + min(a[3], b[3])) / 2.0)
                first, second = (a, b) if a[k] <= b[k] else (b, a); first[k + 2] = m; second[k] = m
        got = whole([p for p in parts if ar(p) > 0 and int((gray[max(0, int(p[1])):int(np.ceil(p[3])), max(0, int(p[0])):int(np.ceil(p[2]))] < 128).sum()) >= 20])
        if got: return got
    if rgb is None: return [list(box)]
    pad = int(max(24, 0.2 * min(x2 - x1, y2 - y1))); rx0, ry0 = max(0, int(x1) - pad), max(0, int(y1) - pad); rx1, ry1 = min(W, int(x2) + pad), min(H, int(y2) + pad)
    white, dark = near_and_dark(gray[ry0:ry1, rx0:rx1], rgb[ry0:ry1, rx0:rx1], paper_of(rgb, box))
    n, lab = cv2.connectedComponents(white.astype(np.uint8), connectivity=4)
    tx0, ty0, tx1, ty1 = bx0 - rx0, by0 - ry0, bx1 - rx0, by1 - ry0
    cnt = np.bincount(lab[ty0:ty1, tx0:tx1].ravel(), minlength=n); cnt[0] = 0
    big = [k for k in range(1, n) if cnt[k] >= 0.15 * max(1, cnt.sum())]
    if len(big) < 2: return [list(box)]
    parts = []
    for k in big:
        comp = lab == k; f = fill_holes(comp)[ty0:ty1, tx0:tx1]
        if int((f & ~comp[ty0:ty1, tx0:tx1] & dark[ty0:ty1, tx0:tx1]).sum()) < 20: continue
        ys, xs = np.nonzero(f); parts.append([int(xs.min()) + bx0, int(ys.min()) + by0, int(xs.max()) + 1 + bx0, int(ys.max()) + 1 + by0])
    return whole(parts) or [list(box)]

def read_page(image, detector, reader, name=None, double_read=False, language="ja", order=None):
    system = system_of(language); ja = system == "japanese"
    if order not in (None, "rtl", "ltr"): raise ValueError(f'order is "rtl" or "ltr", not {order!r}')
    img = image.convert("RGB"); rgb = np.asarray(img); gray = np.asarray(img.convert("L")); W, H = img.size
    dets, bubbles = detector(img)
    if not ja:                                                      # a balloon's text box that lies over two balloons: a block a balloon (Japanese is read as the detector gives it)
        apart = []
        for d in dets:
            parts = over_balloons(d["box"], bubbles, gray, rgb, [o["box"] for o in dets if o is not d]) if d["cls"] == "text_bubble" else [d["box"]]
            apart += [d] if len(parts) < 2 else [dict(d, box=[float(v) for v in p], split=len(parts), of=id(d)) for p in parts]
        dets = apart
    blocks, feats = [], []
    for d in dets:
        box = d["box"]; in_balloon = d["cls"] == "text_bubble"
        cb = {"box": box, "cls": d["cls"]}
        found = interior(gray, box, bubbles, rgb, hull=not ja) if in_balloon else None
        if not in_balloon and not ja:                               # a caption box the page detector did not call a balloon
            found = caption_box(gray, box, bubbles, rgb); in_balloon = found is not None
            if in_balloon: cb["cls"] = "text_bubble"
        cut = crop_box(box, W, H) if ja else line_crop(box, found, gray, rgb, W, H)
        crop = img.crop(cut)
        if d.get("split"):                                          # a part made of the same text box that lies inside this one (a small balloon in the corner of a large one's box): its lettering is not read here
            inside = [o["box"] for o in dets if o is not d and o.get("of") == d["of"] and (o["box"][2] - o["box"][0]) * (o["box"][3] - o["box"][1]) < (box[2] - box[0]) * (box[3] - box[1])
                      and max(0.0, min(o["box"][2], box[2]) - max(o["box"][0], box[0])) * max(0.0, min(o["box"][3], box[3]) - max(o["box"][1], box[1])) >= 0.9 * (o["box"][2] - o["box"][0]) * (o["box"][3] - o["box"][1])]
            if inside:
                crop = crop.copy(); colour = tuple(int(v) for v in (found["paper"] if found is not None else paper_of(rgb, box)))
                for o in inside: crop.paste(colour, [int(o[0] - cut[0]), int(o[1] - cut[1]), int(o[2] - cut[0]), int(o[3] - cut[1])])
        got = None if ja else own_lines(reader.block(crop), cut, box, [o["box"] for o in dets if o is not d], JOIN.get(system, " "))
        text = reader(crop) if ja else got["text"]
        if d["cls"] != "text_bubble" and in_balloon and not soundwords.dialogue(text, system):    # a caption is a sentence; a word in a box is a sign or a label
            found, in_balloon = None, False; cb["cls"] = d["cls"]
        blk = {"id": None, "box": box, "crop": [int(v) for v in cut], "score": d["score"], "kind": None, "vertical": False, "balloon": None, "text": text,
               "readings": [], "numbers": [], "sound": None, "glyph_px": None, "cell_px": None, "look": []}
        joined = None
        if d.get("split"): blk["split"] = d["split"]                 # one of that many blocks made of one text box of the detector's
        if found is not None:
            blk["balloon"] = {"found": found["how"], "closed": bool(found["closed"]), "outline": outline(found), "paper": found["paper"], "ink": found["ink"]}
            joined = found["joined"]
        light = found is None or gray_of(found["paper"]) >= 128
        a = analyse(gray, cb, bubbles, rgb) if ja and in_balloon and light else None
        if a is not None:
            kept, dropped = furigana.read(gray, a, text, reader)
            if dropped: blk["look"].append("a furigana strip was not read: " + ", ".join(x["base"] for x in dropped))
            if re.search(r"\d", digits.nfkc(text)):
                text2, changes = digits.fix(gray, a, text, reader)
                if changes:
                    blk["numbers"] = changes; blk["look"].append("a sideways number was re-read")
                    for r in kept:                                   # the readings keep their place in the changed text
                        j = text2.find(r["base"], max(0, r["start"] - 4))
                        if j < 0: j = text2.find(r["base"])
                        if j >= 0: r["start"], r["end"] = j, j + len(r["base"])
                    text = blk["text"] = text2
            for r in kept:
                if r["note"] and r["note"].startswith("the dictionary reads"): blk["look"].append(f'the reading of {r["base"]} is not the dictionary\'s')
            blk["readings"] = kept
        if ja and re.search(r"\d", digits.nfkc(text)): blk["look"].append("digits")
        if ja and double_read:
            big = crop.resize((int(crop.width * 1.5), int(crop.height * 1.5)), Image.LANCZOS)
            if soundwords.letters(reader(big)) != soundwords.letters(blk["text"] if not blk["numbers"] else reader(crop)):
                blk["look"].append("the reader's two readings differ")
        ink, _, _ = ink_of(gray, cb, bubbles, rgb)
        sw, G = soundwords.strokes(ink)
        blk["glyph_px"] = None if G is None else round(G, 1)
        if ja:
            blk["vertical"] = japanese_columns(in_columns(ink), box)
            n_ja = max(1, len(re.sub(r"[！？!?…．。、・ー―\-.:：\s]", "", text)))
            blk["cell_px"] = round(((box[2] - box[0]) * (box[3] - box[1]) / (1.15 * n_ja)) ** 0.5, 1)     # the lettering's cell: glyph and spacing
        else:
            blk["vertical"] = bool(got["vertical"]); blk["lines"] = [l["text"] for l in got["lines"]]
            blk["line_boxes"] = [[int(round(l["box"][0] + cut[0])), int(round(l["box"][1] + cut[1])), int(round(l["box"][2] + cut[0])), int(round(l["box"][3] + cut[1]))] for l in got["lines"]]   # where each line stands on the page: what `set` paints over
            if got["sure"] is not None: blk["sure"] = round(got["sure"], 2)
            hs = [(l["box"][2] - l["box"][0]) if got["vertical"] else l["thick"] for l in got["lines"]]
            if hs:
                h = float(np.median(hs)); t = soundwords.letters(text)
                if system in ("chinese", "korean"): blk["cell_px"] = round(1.15 * h, 1)
                else: blk["cell_px"] = round(1.25 * (h / 0.72 if t and t == t.upper() and t != t.lower() else h), 1)
            if any(l["confidence"] < 0.6 for l in got["lines"]): blk["look"].append("the reader is unsure of a line")
            if in_balloon and not got["lines"]: blk["look"].append("no line of text was found in the block")
        feats.append({"in_balloon": in_balloon, "joined": joined, "sw": sw, "G": G, "dialogue": in_balloon and soundwords.dialogue(text, system)})
        blocks.append(blk)
    dsw = [f["sw"] for f in feats if f["dialogue"] and f["sw"]]; dG = [f["G"] for f in feats if f["dialogue"] and f["G"]]
    psw = float(np.median(dsw)) if dsw else None; pG = float(np.median(dG)) if dG else None
    for blk, f in zip(blocks, feats):
        stroke = f["sw"] / psw if psw and f["sw"] else None; size = f["G"] / pG if pG and f["G"] else None
        snd, why = soundwords.decide(f["in_balloon"], blk["text"], f["joined"], stroke, size, system)
        blk["kind"] = "sound" if snd else ("speech" if f["in_balloon"] else "outside")
        if snd: blk["sound"] = {"why": why}
        if f["G"] is not None and (f["G"] < 14 or (pG and f["G"] < 0.55 * pG)) and not snd: blk["look"].append("very small text")
    if not ja: on_open_ground(blocks, feats, bubbles, gray, rgb, system, psw, pG)
    inb = [b for b, f in zip(blocks, feats) if f["in_balloon"] and len(soundwords.letters(b["text"])) >= 2]
    vertical = bool(inb) and sum(b["vertical"] for b in inb) > len(inb) / 2.0
    rtl = (order == "rtl") if order else rtl_of(language, vertical)
    blocks = [blocks[i] for i in reading_order([b["box"] for b in blocks], rtl=rtl, stacked=not ja)]
    for n, b in enumerate(blocks, 1): b["id"] = f"b{n:02d}"
    return {"image": name, "width": W, "height": H, "language": language or "ja", "direction": "rtl" if rtl else "ltr", "vertical": vertical,
            "dialogue_glyph_px": None if pG is None else round(pG, 1), "order": [b["id"] for b in blocks], "blocks": blocks}

def images_of(paths):
    """Files, or every image file of a folder, in name order."""
    out = []
    for p in paths:
        if os.path.isdir(p):
            out += [os.path.join(p, f) for f in sorted(os.listdir(p)) if f.lower().endswith((".jpg", ".jpeg", ".png", ".webp", ".bmp"))]
        else: out.append(p)
    return out

def read_files(paths, detector, reader, double_read=False, language="ja", order=None):
    pages = []
    for p in images_of(paths):
        with Image.open(p) as im:
            pages.append(read_page(im, detector, reader, name=os.path.basename(p), double_read=double_read, language=language, order=order))
    return {"pages": pages}
