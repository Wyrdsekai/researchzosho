"""set: text back into the balloons of a page. Input: the page image, the page's dict from `read`, and per block id
the text to set, its language, and for Chinese and Japanese whether it is set in columns. Output: the image and a
report per block (size used, lines, words broken, or why the block was not set).

Every language:
  - Only ink inside the balloon is painted over, 2 px inside its outline; the outline and tail are never touched.
    A dark part that reaches the edge of the inside is left, being the outline or the art coming in; and where the
    inside is the detector's box, not the balloon's own shape ("clipped" in the balloon's `found`), only what lies in
    the block's text box is painted (own_lettering). The paint is the same whatever language is set.
  - The paint is the balloon's `paper` and the new lettering its `ink` when the page dict has them (a yellow caption
    stays yellow, a black balloon keeps white lettering); without them white paint and black ink, the old lettering
    being every pixel of grey 190 or darker.
  - A block whose old lettering cannot be painted away because it is drawn into the art (more than DRAWN of the
    middle of its text box stays dark under the paint) is neither painted nor set: "the lettering is drawn into the
    art: left as art". Nor is a balloon more than half dark for which the page dict names no `paper`: "the balloon
    is dark and the page names no paper and ink for it: left as it is" (with its paper and ink named it is set).
  - A sound word and text outside a balloon are not set and not erased.
  - A block is set in the first font given that has every letter of its text: the font of the target first, then the
    others. A block no font has every letter for is not set, and the report names the letters.
  - Lines are balanced to the balloon: for a size and a number of lines the breaks are chosen so that every line
    leaves about the same share of the room the balloon offers at that line's place.
  - The report's `overlap_px` counts the pixels of new lettering that a later block painted over or lettered over:
    0 on a page set right (blocks that share a balloon have each their own part of it: own_parts).
  - `keep` names strings no line or column ends inside and no hyphen falls in (names, terms). A block they would make
    smaller than four fifths of the page's size is set without them, and its report says which under `keep_broken`.
  - Right-to-left text is refused, not faked. Thai and the Indic scripts are not set: no font given has their letters.

Alphabetic languages (Latin, Cyrillic, Greek); the lines, sizes and glyphs are what they were in 0.1.0:
  - One text size per page: the largest size at least four fifths of the page's balloons can take, capped at 0.8 of
    the page's median original cell size (glyph and spacing, as read gives it); a balloon that cannot take it gets the largest size of its own.
  - A word of 8 letters or more may be broken where the language's patterns allow, 3 letters at least on each side;
    a size up to 3 px smaller without a broken word is preferred. With no pattern file for the language no word is
    ever broken.
  - Capitals as each alphabet writes them: Greek capitals carry no accent (Καλημέρα is set ΚΑΛΗΜΕΡΑ), the diaeresis stays.
    A few characters are set as the one that looks the same and every font has: the modifier apostrophe of Ukrainian and
    Belarusian as the right single quotation mark, a no-break space as a space, a no-break hyphen as a hyphen.

Chinese, Japanese and Korean (a language code beginning ja, zh or ko; zh-Hant, zh-TW and zh-HK are traditional
Chinese, every other zh simplified):
  - No capitals, and the punctuation as the translator wrote it. Where a line may end is kinsoku.py's: never against
    the rules of kinsoku, free where a phrase ends, at a cost inside a phrase, at a larger cost before a particle and
    the largest in the middle of a word; Korean at its spaces, a long word between syllables at the cost of a hyphen.
    A size up to 3 px smaller at which no line ends at a dear place and no line is a single character or marks only
    is preferred; and a size down to four fifths of the page's size at which no line ends in the middle of a word and
    no Korean word is broken; never under 20 px on a 2000-px page for the sake of a line end.
  - Lines: centred by their ink, the pitch 1.25 of the size; a run of two or more ！ ？ closed up to half an em a mark. Columns (`"vertical": true`, Chinese and Japanese only;
    Korean is always set in lines): from the right, hung from one line as a letterer hangs them, the whole block in the
    middle of the balloon, the pitch 1.4 of the size; what stands in each cell is vertical.py's.
  - One text size per page: the largest size four fifths of the balloons can take, and no larger than the original
    allows: the new glyphs as large as the page's median original glyph where the original is Chinese, Japanese or
    Korean, 1.1 times as large where it is alphabetic (measured on a comic lettered professionally in English,
    Japanese and Chinese). The font's glyphs fill 0.88 (Japanese), 0.95 (Chinese), 0.93 (Korean) of its size.
  - The glyphs are placed one by one with Pillow's own layout, never with a shaping library, so a page comes out the
    same on every machine."""
import math
import os
import re
import unicodedata
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from . import hyphen, kinsoku, vertical
from .balloon import mask_of

LEAD = 1.12                   # a line's pitch over the text size, alphabetic text
LEAD_CJK = 1.25               # the same for horizontal lines of Chinese, Japanese and Korean
LEAD_COLUMNS = 1.4            # a column's pitch over the text size
COST = {"hyph": 0.6, "syl": 0.6, "soft": 0.1, "in": 0.8, "mid": 1.6}       # what ending a line at such a place adds to the search's cost
DRAWN = 0.03                  # a block whose text box keeps more than this share of its middle dark under the paint has its lettering drawn into the art: it is left as art.
                              # Chosen on 473 balloons (159 of a manga, 310 of a colour comic in five languages, 4 drawn): 472 keep nothing dark there (0.0000),
                              # and one, a telephone's ring drawn through its balloon and read as speech, keeps 0.18. A lettering's own ink fills about 0.19 of that middle.
SMALLER = 0.8                 # a block is made smaller, down to this share of the page's size, to spare a break in the middle of a word (Chinese, Japanese, Korean) or inside a kept string
READABLE = 20                 # the smallest size Chinese, Japanese and Korean read at without effort, in px on a page 2000 px high
LONE = 0.3                    # what a line of one character costs among the lines of Chinese, Japanese or Korean
INK = {"jp": 0.88, "sc": 0.95, "tc": 0.95, "kr": 0.93}     # the glyph size `read` would measure on our CJK font, over its text size
GLYPH_FROM_CJK = 1.0          # Chinese, Japanese or Korean glyphs over the original's, where the original is one of the three
GLYPH_FROM_ALPHABET = 1.1     # the same where the original is alphabetic
GLYPH_FROM_HANGUL = 1.25      # Chinese or Japanese glyphs over a Korean original's. Hangul is lettered smaller than Chinese characters or kana in the same balloons: the Korean
                              # edition's glyphs measured 0.78 of the Chinese edition's over 20 pages of one comic (a brush hand) and 0.80 over 10 pages of another (type),
                              # two letterers, 2026-11-14. At 1.0 a page from a Korean original came out a fifth smaller than the same page from its English or Chinese edition.
SAME_LOOK = {"\u02bc": "\u2019", "\u00a0": " ", "\u2011": "-"}

def as_set(text, caps=True):
    """The text as it is drawn: the look-alike characters replaced, and in capitals when asked, as its alphabet writes them."""
    t = "".join(SAME_LOOK.get(c, c) for c in text)
    if not caps: return t
    t = t.upper()
    if any(0x370 <= ord(c) <= 0x3FF or 0x1F00 <= ord(c) <= 0x1FFF for c in t):          # Greek: capitals without the accent
        t = unicodedata.normalize("NFC", "".join(c for c in unicodedata.normalize("NFD", t) if c != "\u0301"))
    return t

def script_problem(text):
    """None when the text can be set; else why not."""
    for c in text:
        if unicodedata.bidirectional(c) in ("R", "AL"): return "right-to-left script: needs bidirectional reordering and shaping, not done here"
    return None

MARKS = re.compile("([！？]{2,})")       # a run of full-width ！ and ？: set at half an em a mark, as a letterer closes them up

class Tight:
    """What layout() asks of a font, for a line of Chinese, Japanese or Korean: a piece's length with its runs of
    two or more ！ ？ closed up to half an em a mark."""
    def __init__(self, font, size): self.font, self.size = font, size
    def getlength(self, piece):
        return self.font.getlength(piece) - 0.5 * self.size * sum(len(m) for m in MARKS.findall(piece))

class Way:
    """How a block in Chinese, Japanese or Korean is set: whose conventions (kinsoku.region), in columns or in lines,
    and the pitch of its lines or columns over the text size."""
    def __init__(self, lang, columns=False):
        self.lang, self.region = lang, kinsoku.region(lang)
        self.vertical = bool(columns) and self.region in ("jp", "sc", "tc")          # Korean is always set in lines
        self.lead = LEAD_COLUMNS if self.vertical else LEAD_CJK

def atoms_of(text, hyph):
    """[(piece, kind)]: 'word' a word ends here; 'hyph' may break here with a hyphen added; 'nat' may break here with
    nothing added (after the word's own hyphen; not after a hyphen between two digits: a date, a range of numbers)."""
    out = []
    for tok in text.split():
        parts = re.split(r"(?<=[-–—])(?=\w)(?!(?<=\d[-–—])\d)", tok)
        for pi, part in enumerate(parts):
            m = max(re.finditer(r"[^\W\d_]+", part), key=lambda m: len(m.group()), default=None)
            cuts = [m.start() + c for c in hyph.positions(m.group())] if (hyph and m and len(m.group()) >= 8) else []
            prev = 0
            for c in cuts:
                out.append((part[prev:c], "hyph")); prev = c
            out.append((part[prev:], "word" if pi == len(parts) - 1 else "nat"))
    return out

def kept(atoms, keep):
    """The atoms with every piece of a kept string (a name, a term) joined into one, so that no line ends inside it
    and no hyphen falls in it. `keep` holds the strings as they are drawn. The same list when nothing is joined."""
    sep = {"word": " ", "wide": "\u3000"}; text = ""; cuts = []
    for piece, kind in atoms:
        text += piece; cuts.append(len(text)); text += sep.get(kind, "")           # the text as it is drawn, and where each atom ends in it
    inside = set()
    for name in keep:
        i = text.find(name) if name else -1
        while i >= 0:
            inside.update(c for c in cuts if i < c < i + len(name)); i = text.find(name, i + 1)
    if not inside: return atoms
    out, join = [], False
    for (piece, kind), c in zip(atoms, cuts):
        if join: out[-1] = (out[-1][0] + sep.get(out[-1][1], "") + piece, kind)
        else: out.append((piece, kind))
        join = c in inside
    return out

def layout(atoms, widths, f, allow_hyphen, memo=None, strict=0, lone=0.0):
    """The atoms in exactly len(widths) lines, balanced: (cost, lines, words broken, breaks inside a phrase, of
    them in the middle of a word) or None.
    An atom is (piece, kind), the kind saying what may happen after the piece: 'word' a space follows and a line may
    end here; 'wide' the same with the full-width space of Chinese and Japanese; 'nat' a line may end here and nothing
    is added; 'hyph' a line may end here with a hyphen added, at a
    cost and only when allow_hyphen; 'syl' the same with nothing added (inside a Korean word); 'soft' a line may end
    here at a small cost (inside a phrase of Chinese or Japanese, where a word begins); 'in' at a larger cost (inside
    a phrase, before a particle or an ending); 'mid' at the largest cost (in the middle of a word). `strict` 1: no
    line ends at a 'mid'; 2: none at an 'in' or a 'mid', and no line is one character or marks only. `lone`: what
    such a line adds to the cost.
    `f` measures a piece (a font, or a column's measure); `memo` keeps the measures between calls with the same
    atoms and the same f."""
    N, n = len(atoms), len(widths)
    if N < n: return None
    if memo is None: memo = {}
    if "wd" not in memo:
        memo["wd"] = ([f.getlength(a) for a, _ in atoms], f.getlength(" "), f.getlength("-"))
        memo["wide"] = f.getlength("\u3000") if any(kind == "wide" for _, kind in atoms) else 0.0
    wd, sp, hy = memo["wd"]; sp2 = memo["wide"]
    def width(j, j2):
        if (j, j2) in memo: return memo[(j, j2)]
        w = 0.0
        for i in range(j, j2):
            w += wd[i]
            if i < j2 - 1 and atoms[i][1] == "word": w += sp
            elif i < j2 - 1 and atoms[i][1] == "wide": w += sp2
        w = memo[(j, j2)] = w + (hy if atoms[j2 - 1][1] == "hyph" else 0.0)
        return w
    INF = float("inf")
    best = [[INF] * (N + 1) for _ in range(n + 1)]; back = [[None] * (N + 1) for _ in range(n + 1)]; best[0][0] = 0.0
    for k in range(1, n + 1):
        Wk = widths[k - 1] * 0.98
        for j2 in range(k, N + 1):
            kind = atoms[j2 - 1][1]
            if kind in ("hyph", "syl") and (not allow_hyphen or j2 == N): continue
            if strict and j2 < N and (kind == "mid" or (kind == "in" and strict > 1)): continue
            for j in range(j2 - 1, k - 2, -1):
                if best[k - 1][j] == INF: continue
                w = width(j, j2)
                if w > Wk: break
                c = best[k - 1][j] + ((Wk - w) / Wk) ** 2 + (COST.get(kind, 0.0) if j2 < N else 0.0)
                if lone and j2 - j == 1 and n > 1 and (len(atoms[j][0]) == 1 or not any(ch.isalnum() for ch in atoms[j][0])):
                    if strict > 1: continue
                    c += lone
                if c < best[k][j2]: best[k][j2] = c; back[k][j2] = j
    if best[n][N] == INF: return None
    lines, j2, hyph, soft, mid = [], N, 0, 0, 0
    for k in range(n, 0, -1):
        j = back[k][j2]; s = ""
        for i in range(j, j2):
            s += atoms[i][0]
            if i < j2 - 1 and atoms[i][1] == "word": s += " "
            elif i < j2 - 1 and atoms[i][1] == "wide": s += "\u3000"
        if atoms[j2 - 1][1] == "hyph": s += "-"; hyph += 1
        elif atoms[j2 - 1][1] == "syl": hyph += 1
        elif atoms[j2 - 1][1] in ("in", "mid") and j2 < N: soft += 1; mid += atoms[j2 - 1][1] == "mid"
        lines.append(s); j2 = j
    return best[n][N], lines[::-1], hyph, soft, mid

def runs(inner):
    ys, xs = np.nonzero(inner)
    if len(xs) == 0: return None
    cxm = int(np.median(xs)); L = np.zeros(inner.shape[0], int); R = np.zeros(inner.shape[0], int)
    for y in np.unique(ys):
        row = inner[y]
        if not row[cxm]:
            d = np.diff(np.concatenate([[0], row.view(np.int8), [0]])); s = np.nonzero(d == 1)[0]; e = np.nonzero(d == -1)[0]
            k = int((e - s).argmax()); L[y], R[y] = s[k], e[k]; continue
        l = cxm
        while l > 0 and row[l - 1]: l -= 1
        r = cxm
        while r < len(row) - 1 and row[r + 1]: r += 1
        L[y], R[y] = l, r + 1
    return L, R

def colours(blk):
    """(paper, ink) of a block's balloon as `read` gave them, each (r, g, b); (None, None) when the page dict has no
    paper: white paper and black ink, the lettering found by the page's grey as it always was."""
    b = blk.get("balloon") or {}; ok = lambda c: isinstance(c, (list, tuple)) and len(c) == 3
    if not ok(b.get("paper")): return None, None
    paper = tuple(int(round(v)) for v in b["paper"])
    if ok(b.get("ink")): return paper, tuple(int(round(v)) for v in b["ink"])
    return paper, ((0, 0, 0) if 0.299 * paper[0] + 0.587 * paper[1] + 0.114 * paper[2] >= 128 else (255, 255, 255))

CONTRAST = 2.0       # under this contrast of ink on paper a block is left as it is: the lowest of 1,097 speech blocks of eight real comics is 3.2

def contrast(a, b):
    """The contrast of two colours as the web's accessibility rules measure it, 1 (the same) to 21 (black on white)."""
    def lum(c):
        f = [v / 255.0 for v in c]; f = [v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4 for v in f]
        return 0.2126 * f[0] + 0.7152 * f[1] + 0.0722 * f[2]
    hi, lo = sorted((lum(a), lum(b)), reverse=True)
    return (hi + 0.05) / (lo + 0.05)

def lettering(rgb, paper, ink):
    """Where a region of the page is lettering and not paper: a pixel a quarter of the way or more from the paper's
    colour towards the ink's (65/255, the share at which grey 190 lies between white and black)."""
    px = rgb.astype(np.float32) - np.array(paper, np.float32); d = np.array(ink, np.float32) - np.array(paper, np.float32); dd = float((d * d).sum())
    if dd < 3 * 40.0 ** 2: return np.abs(px).max(axis=2) > 40             # ink and paper too alike to give a direction
    return (px * d).sum(axis=2) / dd >= 65.0 / 255.0

def own_lettering(ink, safe, blk, ox, oy):
    """Of the dark parts found inside a balloon, those that are the block's own old lettering: what is painted over.
    Lettering stands on the paper, clear of the balloon's edge. So a dark part that reaches the edge of the inside is
    left: it is the outline, or the art coming in where the inside is not the balloon's own shape; but not where it
    lies in a line the reader read (`line_boxes`, 2 px of margin), which is lettering whatever it touches. Where the inside is
    the detector's box ("clipped" in the balloon's `found`) nothing is painted either that stands clear, by half a
    glyph, of the rows of the lines the reader read (`line_boxes`), each taken across the line and the text box (the
    block's text box alone where the page dict has no lines): the art and a neighbour's lettering may lie over or
    under the lines in that box, and the text box itself is often a letter short of a line's start. A speck under
    5 px is left."""
    n, parts, stats, _ = cv2.connectedComponentsWithStats(ink, connectivity=8)
    if n <= 1: return ink
    leave = np.zeros(n, bool); leave[np.unique(parts[safe & ~(cv2.erode(safe.astype(np.uint8), np.ones((3, 3), np.uint8)) > 0)])] = True
    clipped = "clipped" in (blk["balloon"].get("found") or "")
    if clipped: leave |= stats[:, cv2.CC_STAT_AREA] < 5
    leave[0] = False; out = ink.copy(); out[leave[parts]] = 0
    if blk.get("line_boxes"):                                      # a line the reader read is lettering though it reaches the edge: hand lettering runs up to its outline and into it
        read = np.zeros(ink.shape, bool)
        for x1, y1, x2, y2 in blk["line_boxes"]: read[max(0, int(y1) - 2 - oy):max(0, int(y2) + 2 - oy), max(0, int(x1) - 2 - ox):max(0, int(x2) + 2 - ox)] = True
        out[read & (ink > 0)] = 1
    if clipped:                                                    # by the lines the reader read where the page dict has their boxes (every language but Japanese), else by the text box
        g = max(6.0, 0.5 * float(blk.get("glyph_px") or 12.0)); own = np.zeros(ink.shape, bool); bx1, _, bx2, _ = blk["box"]
        for x1, y1, x2, y2 in (blk.get("line_boxes") or [blk["box"]]):       # a line's rows, across the line and the text box: the marks at its ends (an ellipsis) are the line's own
            own[max(0, int(y1 - g) - oy):max(0, int(y2 + g) - oy), max(0, int(min(x1, bx1) - g) - ox):max(0, int(max(x2, bx2) + g) - ox)] = True
        out[~own] = 0
    return out

def paint_of(rgb, gray, blk, filled, ox, oy, others=None):
    """What is painted over in a block's balloon: (the mask over the balloon's region, the paint's colour, the new
    lettering's colour, the share of the middle of the text box that stays dark under the paint, the share of the
    whole inside that is dark before it).
    The old lettering is every pixel 2 px or more inside the outline that is dark (grey 190 or darker; with a `paper`
    in the page dict: a quarter of the way or more from the paper's colour to the ink's), and every such pixel of a
    line the reader read that lies in the hull of the inside (lines_in_the_hull) and in no other block's part
    (`others`: a mask over the inside's region of what other blocks own, from own_parts), less what own_lettering()
    leaves; it is painted with a margin of 2 px. One rule for every target: what is painted over is the source's
    lettering, whatever is set in its place.
    The share is the guard against lettering that is drawn into the art (DRAWN): the dark pixels the paint does not
    cover in the middle of the block's text box (the box less a quarter of its width and of its height on each
    side), over that middle's pixels inside the balloon. Lettering that stands on its paper is painted away whole
    there; art that comes into a balloon lies at the box's edges, not in its middle."""
    reg = gray[oy:oy + filled.shape[0], ox:ox + filled.shape[1]]
    safe = cv2.erode(filled.astype(np.uint8), np.ones((5, 5), np.uint8)) > 0          # 2 px inside the outline
    reach = lines_in_the_hull(filled, blk, ox, oy)
    safe = safe | (reach if others is None else reach & ~others)      # never into what another block owns: the hull of a part with a corner cut out of it takes the corner in
    paper, pen = colours(blk)
    if paper is None or (paper, pen) == ((255, 255, 255), (0, 0, 0)): paper, pen = (255, 255, 255), (0, 0, 0); dark = (safe & (reg <= 190)).astype(np.uint8)
    else: dark = (safe & lettering(rgb[oy:oy + filled.shape[0], ox:ox + filled.shape[1]], paper, pen)).astype(np.uint8)
    paint = (cv2.dilate(own_lettering(dark, safe, blk, ox, oy), np.ones((5, 5), np.uint8)) > 0) & safe
    x1, y1, x2, y2 = blk["box"]; w, h = 0.25 * (x2 - x1), 0.25 * (y2 - y1); middle = np.zeros(filled.shape, bool)
    middle[max(0, int(y1 + h) - oy):max(0, int(y2 - h) - oy), max(0, int(x1 + w) - ox):max(0, int(x2 - w) - ox)] = True
    room = int((safe & middle).sum())
    return paint, paper, pen, (float((dark.astype(bool) & middle & ~paint).sum()) / room if room else 0.0), float(dark.sum()) / max(1, int(safe.sum()))

def lines_in_the_hull(filled, blk, ox, oy):
    """Where a line the reader read lies in the hull of the balloon's inside, 2 px from the hull's edge: a mask over
    the inside's region, empty where the page dict has no `line_boxes` (Japanese) or the inside is the detector's box
    ("clipped": no shape to take a hull of).
    A letter joined to the outline is not enclosed by the paper, so it is no part of the inside, and with it goes the
    paper it closes off between itself and its neighbours: a hand-lettered first line that runs into its balloon's
    outline leaves a bay in the inside as wide as the line. The hull closes the bay; a line's rectangle (2 px of
    margin) says where in it there is lettering, when a third of it or more lies in the inside itself or four fifths
    in the hull (measured on 590 lines of 14 hand-lettered pages: a first line that runs into its outline lies 43 to
    60 % in the inside; a line wholly cut off from the paper 0 to 26 % in the inside and 86 to 100 % in the hull; the
    one line of a neighbouring balloon that a block held 1 % and 19 %). Outside the lines
    the inside is as it was, so the outline stays everywhere else; over the line it stays to 2 px inside the hull's
    own edge, and where the lettering ran over the outline the outline goes with it."""
    out = np.zeros(filled.shape, bool)
    if not blk.get("line_boxes") or "clipped" in ((blk.get("balloon") or {}).get("found") or "") or not filled.any(): return out
    hull = np.zeros(filled.shape, np.uint8); cv2.fillConvexPoly(hull, cv2.convexHull(cv2.findNonZero(filled.astype(np.uint8))), 1)
    for x1, y1, x2, y2 in blk["line_boxes"]:
        ya, yb, xa, xb = max(0, int(y1) - oy), max(0, int(y2) - oy), max(0, int(x1) - ox), max(0, int(x2) - ox); area = max(1, (int(y2) - int(y1)) * (int(x2) - int(x1)))
        if 3 * int(filled[ya:yb, xa:xb].sum()) < area and int(hull[ya:yb, xa:xb].sum()) < 0.8 * area: continue      # another balloon's line, which the block holds too
        out[max(0, ya - 2):yb + 2, max(0, xa - 2):xb + 2] = True
    return out & (cv2.erode(hull, np.ones((5, 5), np.uint8)) > 0)

def own_parts(blocks, shape):
    """The inside of each speech block's balloon, {id: (mask, (ox, oy))}, with no pixel of the page in two of them:
    a pixel that two blocks' insides both hold goes to the block whose text box is the nearer (to the box's edge;
    then to its middle; then the block that comes first).
    The page detector makes two or three text blocks of a balloon lettered in groups of columns. They have the whole
    balloon for their inside, or, where the inside is clipped to a box, insides that lie over each other by a strip:
    set there, their translations lay one over the other (ten places in eleven pages of one comic; and, with only the
    blocks of one same inside kept apart, still one on a page where the third block's inside was its own). Every pair
    of insides that share a pixel is kept apart, whatever the share. A block alone in its balloon has the whole
    inside, as before."""
    got = {}
    for b in blocks:
        if b.get("kind") != "speech" or not b.get("balloon") or len(b["balloon"].get("outline") or []) < 3: continue
        f, (ox, oy) = mask_of(b["balloon"]["outline"], shape)
        if f.any(): got[b["id"]] = [f.copy(), ox, oy, b["box"], len(got)]
    def away(xx, yy, box):
        d = np.hypot(np.maximum(np.maximum(box[0] - xx, xx - box[2]), 0), np.maximum(np.maximum(box[1] - yy, yy - box[3]), 0))
        return d, np.hypot(xx - (box[0] + box[2]) / 2.0, yy - (box[1] + box[3]) / 2.0)
    ids = list(got)
    for a in range(len(ids)):
        for c in range(a + 1, len(ids)):
            fi, oxi, oyi, bi, _ = got[ids[a]]; fj, oxj, oyj, bj, _ = got[ids[c]]
            x0, y0 = max(oxi, oxj), max(oyi, oyj); x1, y1 = min(oxi + fi.shape[1], oxj + fj.shape[1]), min(oyi + fi.shape[0], oyj + fj.shape[0])
            if x1 <= x0 or y1 <= y0: continue
            vi, vj = fi[y0 - oyi:y1 - oyi, x0 - oxi:x1 - oxi], fj[y0 - oyj:y1 - oyj, x0 - oxj:x1 - oxj]; both = vi & vj
            if not both.any(): continue
            yy, xx = np.mgrid[y0:y1, x0:x1]; (di, ci), (dj, cj) = away(xx, yy, bi), away(xx, yy, bj)
            first = (di < dj) | ((di == dj) & (ci <= cj))              # the earlier block's, where its text box is the nearer or as near
            vi[both & ~first] = False; vj[both & first] = False
    return {i: (f, (ox, oy)) for i, (f, ox, oy, _, _) in got.items()}

class Setter:
    def __init__(self, font_path, hyphenation_dir=None, caps=True, sizes=(64, 8), more_fonts=(), words=None):
        """`words`: the folder of the manifest's word lists (words/), for the line ends of Chinese."""
        if words: kinsoku.words_in(words)
        self.font_paths = [font_path] + [p for p in more_fonts if p and p != font_path]
        self.font_path, self.hdir, self.caps = font_path, hyphenation_dir, caps
        self.sizes = range(sizes[0], sizes[1] - 1, -1); self._fonts = {}; self._hyph = {}; self._has = {}; self._blank = {}

    def font(self, size, k=0, plain=False):
        """Font k at a size, opened with Pillow's own layout (ImageFont.Layout.BASIC), which places one glyph after
        another by its advance and is the same on every machine. With no layout named, Pillow shapes text with raqm
        where the system has FriBiDi and with this layout where it has not, and the font's kerning then moves line
        ends and sizes: one comic was lettered at 31 px on one machine and 30 px on two others. `plain` is kept for
        its callers and changes nothing now."""
        if (k, size) not in self._fonts: self._fonts[(k, size)] = ImageFont.truetype(self.font_paths[k], size, layout_engine=ImageFont.Layout.BASIC)
        return self._fonts[(k, size)]

    def lacks(self, text, k=0):
        """The letters of the text that font k has no glyph for: what it draws for them is what it draws for a code point
        no font has."""
        f = self.font(40, k)
        if k not in self._has:
            m = f.getmask("\uffff"); self._has[k] = ((m.getbbox(), bytes(m)), {})
        none, seen = self._has[k]; out = []
        for ch in dict.fromkeys(text):
            if ch.isspace(): continue
            if ch not in seen:
                m = f.getmask(ch); seen[ch] = (m.getbbox(), bytes(m)) != none
            if not seen[ch]: out.append(ch)
        return out

    def font_for(self, text):
        """(the first font that has every letter of the text, None), or (None, the letters the first font lacks)."""
        for k in range(len(self.font_paths)):
            if not self.lacks(text, k): return k, None
        return None, self.lacks(text, 0)

    def hyph(self, lang):
        if lang not in self._hyph: self._hyph[lang] = hyphen.load(self.hdir, lang)
        return self._hyph[lang]

    def fit_at(self, atoms, L, R, size, hyphens=(False, True), k=0, way=None, strict=0):
        """The text at one size in the balloon: (cost, lines, where the first line starts, each line's span, words
        broken, breaks inside a phrase, of them in the middle of a word) or None. L and R are the balloon's inside
        row by row; for columns they are its inside column by column from the right, and a line is a column.
        `strict` is layout's."""
        live = np.nonzero(R - L > 0)[0]
        if len(live) == 0: return None
        top, bot = int(live[0]), int(live[-1]); memo = {}
        if way is None: f = self.font(size, k); lh = int(round(LEAD * size))
        else:
            f = self.font(size, k, plain=True); lh = int(round(way.lead * size))
            f = vertical.Measure(f, size, way.region) if way.vertical else Tight(f, size)
            # the lines together hold no more than the inside's area over the pitch: a size that needs more is not searched
            if sum(f.getlength(a) for a, _ in atoms) > 0.98 * float((R - L)[live].sum()) / lh: return None
            if not any(kind in ("hyph", "syl") for _, kind in atoms): hyphens = hyphens[:1]
        for allow in hyphens:
            best = None
            for n in range(1, max(1, (bot - top + 1) // lh) + 1):
                for shift in (0, -lh // 3, lh // 3):
                    y0 = (top + bot) // 2 - n * lh // 2 + shift
                    if y0 < top or y0 + n * lh > bot + 1: continue
                    spans = []
                    for i in range(n):
                        a, b = y0 + i * lh, y0 + (i + 1) * lh
                        if (R[a:b] - L[a:b] <= 0).any(): spans = None; break
                        l, r = int(L[a:b].max()), int(R[a:b].min())
                        if r - l < size: spans = None; break
                        spans.append((l, r))
                    if not spans: continue
                    if way is not None and way.vertical:           # columns hang from one line: the lowest of their tops
                        t = max(l for l, _ in spans); spans = [(t, r) for _, r in spans]
                        if min(r for _, r in spans) - t < size: continue
                    got = layout(atoms, [r - l for l, r in spans], f, allow, memo, strict, 0.0 if way is None else LONE)
                    if got and (best is None or got[0] + 0.02 * n < best[0]): best = (got[0] + 0.02 * n, got[1], y0, spans, got[2], got[3], got[4])
            if best: return best
        return None

    def largest(self, atoms, L, R, cap=10 ** 6, k=0, way=None):
        for size in self.sizes:
            if size > cap: continue
            got = self.fit_at(atoms, L, R, size, k=k, way=way)
            if got: return size, got
        return 0, None

    def blank(self, ch, k=0):
        """(the empty share of a glyph's advance before its ink, after its ink), for font k: a line that begins with an
        opening bracket or ends with a comma is centred by its ink, not by the empty half of that cell."""
        if (k, ch) not in self._blank:
            f = self.font(100, k, plain=True); im = Image.new("L", (400, 300), 0)
            ImageDraw.Draw(im).text((100, 200), ch, font=f, fill=255, anchor="ls"); bb = im.getbbox(); adv = f.getlength(ch)
            self._blank[(k, ch)] = (0.0, 0.0) if bb is None else (max(0.0, (bb[0] - 100) / 100.0), max(0.0, (100 + adv - bb[2]) / 100.0))
        return self._blank[(k, ch)]

    def pieces(self, line, size, k=0):
        """A line of Chinese, Japanese or Korean as it is drawn: ([(x from the line's start, text)], the left edge of
        its ink, the right edge). A run of two or more ！ ？ stands at half an em a mark, each mark's ink in the middle
        of its half em (a Japanese font draws ！ in the middle of a whole em, a Chinese one at its left)."""
        f = self.font(size, k, plain=True); out = []; x = 0.0; first = last = None
        for seg in MARKS.split(line):
            if not seg: continue
            if MARKS.fullmatch(seg):
                for ch in seg:
                    lb, rb = self.blank(ch, k); adv = f.getlength(ch); at = x + 0.25 * size - (lb * size + adv - rb * size) / 2.0
                    out.append((at, ch)); first = at + lb * size if first is None else first; last = at + adv - rb * size; x += 0.5 * size
            else:
                out.append((x, seg)); first = x + self.blank(seg[0], k)[0] * size if first is None else first; x += f.getlength(seg); last = x - self.blank(seg[-1], k)[1] * size
        return out, first or 0.0, last or 0.0

    def page_size(self, items, height=None):
        """The one size of a page: the largest that four fifths of its balloons take, and no more than the original
        lettering allows. Alphabetic text: 0.8 of the page's median original cell (glyph and spacing). Chinese,
        Japanese and Korean: the size at which the new glyphs are as large as the page's median original glyph where
        the original is itself in one of the three, and 1.1 times as large where it is alphabetic (what the Japanese
        and Chinese letterers of a comic lettered in English were measured to do); Chinese or Japanese from a Korean
        original, 1.25 times as large (GLYPH_FROM_HANGUL). The original lettering never holds such a page under the
        smallest size that reads (READABLE on a page 2000 px high, by `height`): only its balloons do."""
        sizes = sorted(it[15] for it in items); page_px = sizes[int(math.floor(0.2 * len(sizes)))]
        if 2 * sum(1 for it in items if it[11] is not None) <= len(items):
            gl = [it[0].get("cell_px") or 1.5 * it[0]["glyph_px"] for it in items if it[0].get("cell_px") or it[0].get("glyph_px")]
            if gl: page_px = min(page_px, int(math.floor(0.8 * float(np.median(gl)))))
        else:
            gl = [it[0].get("glyph_px") or it[0]["cell_px"] / 1.5 for it in items if it[0].get("cell_px") or it[0].get("glyph_px")]
            wide = sum(1 for it in items if any(unicodedata.east_asian_width(c) in ("W", "F") for c in it[0].get("text") or ""))
            ink = float(np.median([INK.get(it[11].region, 0.92) for it in items if it[11] is not None]))
            hangul = sum(1 for it in items if any("\uac00" <= c <= "\ud7a3" for c in it[0].get("text") or ""))
            korean = 2 * sum(1 for it in items if it[11] is not None and it[11].region == "kr") > len(items)                 # the page is being set in Korean
            over = GLYPH_FROM_ALPHABET if 2 * wide <= len(items) else GLYPH_FROM_HANGUL if 2 * hangul > len(items) and not korean else GLYPH_FROM_CJK
            if gl:
                cap = int(round(over * float(np.median(gl)) / ink))
                if height: cap = max(cap, int(round(READABLE * height / 2000.0)))                                             # never held under the size that reads by the original's lettering
                page_px = min(page_px, cap)
        return max(self.sizes[-1], page_px)

    def least(self, page_px, height, way):
        """The smallest size a block is given for the sake of its line ends or of a kept string: four fifths of the
        page's size (under which a block is small beside its page) and, for Chinese, Japanese and Korean, the
        smallest size that reads, 20 px on a page 2000 px high. The same two terms the program's floor has, so that
        a block made smaller here is not sent back for its size."""
        return max(self.sizes[-1], int(round(SMALLER * page_px)), int(round(READABLE * height / 2000.0)) if way is not None else 0)

    def settle(self, atoms, L, R, own, best, page_px, k, way, least):
        """A block's size and lines on a page whose size is page_px: (size, fit), the fit None when the text does not go
        into the balloon. At the page's size where the block takes it, else at the largest size of its own; then a
        smaller size is preferred where it spares a bad line end. Alphabetic text: up to 3 px smaller without a
        hyphenated word. Chinese, Japanese, Korean: up to 3 px smaller with no line ending inside a phrase; and down
        to `least` with no line ending in the middle of a word and no Korean word broken; never under `least`."""
        if own >= page_px:
            size = page_px; got = self.fit_at(atoms, L, R, page_px, k=k, way=way)
            if got is None: size, got = self.largest(atoms, L, R, cap=page_px, k=k, way=way)
        else: size, got = own, best
        if got is None: return size, None
        near = max(self.sizes[-1], size - 3); far = near
        if way is not None: near = max(near, least); far = least
        if got[4]:                                            # a broken word (a hyphen; a Korean word between syllables)
            for s2 in range(size - 1, far - 1, -1):
                g2 = self.fit_at(atoms, L, R, s2, hyphens=(False,), k=k, way=way)
                if g2: size, got = s2, g2; break
        if way is not None and got[5]:                        # a line ends inside a phrase: up to 3 px smaller where none does;
            for strict, low in (((2, near), (1, far)) if got[6] else ((2, near),)):       # failing that and with a line ending in the middle of a word: smaller still where none does
                g2 = next((g for g in ((s2, self.fit_at(atoms, L, R, s2, hyphens=(False,), k=k, way=way, strict=strict)) for s2 in range(size - 1, low - 1, -1)) if g[1]), None)
                if g2: size, got = g2; break
        return size, got

    def set_page(self, image, page, texts, keep=()):
        """(the page with the texts set, the report). `keep`: strings no line or column may end inside (the names and
        terms decided for the comic, in the language being set). A block that a kept string would make smaller than
        four fifths of the page's size, or keep out of its balloon, is set without it, and its report names the
        strings a line ends inside under `keep_broken`. (For Chinese, Japanese and Korean the limit is also never
        under 20 px on a 2000-px page: least().)"""
        img = image.convert("RGB"); gray = np.asarray(img.convert("L")); rgb = np.asarray(img); report = {}; items = []
        parts = own_parts(page["blocks"], gray.shape)
        owner = np.zeros(gray.shape, np.int32)                    # which block's part each pixel is in, from 1: a block's paint keeps out of every other block's
        for n, (m, (px, py)) in enumerate(parts.values(), 1): owner[py:py + m.shape[0], px:px + m.shape[1]][m] = n
        number = {bid: n for n, bid in enumerate(parts, 1)}
        for blk in page["blocks"]:
            t = texts.get(blk["id"])
            if t is None: continue
            text = " ".join(str(t.get("text", "") if isinstance(t, dict) else t).split()); lang = (t.get("lang") if isinstance(t, dict) else None) or "en"
            rec = report[blk["id"]] = {"set": False, "language": lang}
            way = Way(lang, isinstance(t, dict) and t.get("vertical")) if kinsoku.is_cjk(lang) else None
            if way is not None:                               # a full-width space stays one: Japanese writes it after ！ and ？
                text = re.sub(r"\s+", lambda m: "\u3000" if "\u3000" in m.group() else " ", str(t.get("text", "") if isinstance(t, dict) else t).strip())
            why = None
            if blk["kind"] == "sound": why = "a sound word: left as art"
            elif blk["kind"] != "speech": why = "text outside a balloon"
            elif not blk.get("balloon") or len(blk["balloon"].get("outline") or []) < 3: why = "no balloon inside found"
            elif not re.search(r"\w", text): why = "nothing to set"
            else: why = script_problem(text)
            if why: rec["why_not"] = why; continue
            filled, (ox, oy) = parts.get(blk["id"]) or mask_of(blk["balloon"]["outline"], gray.shape)     # the block's own part of a balloon it shares
            ys, xs = np.nonzero(filled)
            if len(xs) == 0: rec["why_not"] = "no balloon inside found"; continue
            wide = 0.08 if "clipped" in (blk["balloon"].get("found") or "") else 0.04      # a balloon joined to a neighbour keeps more margin
            margin = max(5, round(wide * min(np.ptp(ys) + 1, np.ptp(xs) + 1)))
            inner = cv2.distanceTransform(filled.astype(np.uint8), cv2.DIST_L2, 5) > margin
            rr = runs(inner) if way is None or not way.vertical else runs(np.ascontiguousarray(inner[:, ::-1].T))     # columns: the inside column by column, from the right
            if rr is None: rec["why_not"] = "the balloon is too thin"; continue
            mine = owner[oy:oy + filled.shape[0], ox:ox + filled.shape[1]]
            paint, paper, pen, stays, darkness = paint_of(rgb, gray, blk, filled, ox, oy, (mine > 0) & (mine != number.get(blk["id"], 0)))
            if contrast(paper, pen) < CONTRAST:                 # what no rule foresees: lettering in these two colours would not be read
                rec["why_not"] = "the balloon's paper and its lettering are too close in colour for lettering to be read on it: left as it is"; continue
            if darkness > 0.5 and colours(blk)[0] is None:    # a dark balloon and no paper named for it: white paint and black ink would spoil it
                rec["why_not"] = "the balloon is dark and the page names no paper and ink for it: left as it is"; continue
            if stays > DRAWN: rec["why_not"] = "the lettering is drawn into the art: left as art"; continue
            if way is None:
                hy = self.hyph(lang)
                if hy is None: rec["hyphenation"] = "no patterns for this language: words are not broken"
                shown = as_set(text, self.caps)
            else:
                shown = as_set(text, False)
                if not kinsoku.phrases_known(lang): rec["phrases"] = "the budoux package is not installed: a line may end inside a phrase as readily as after it"
            k, lacking = self.font_for(shown)
            if k is None: rec["why_not"] = "the font has no letter for " + " ".join(lacking[:12]); continue
            if k: rec["other_font"] = os.path.basename(self.font_paths[k])
            atoms = atoms_of(shown, hy) if way is None else kinsoku.atoms(shown, lang)
            names = [n for n in (as_set(" ".join(str(x).split()), self.caps and way is None) for x in keep or ()) if n and n in shown]
            free = None; held = kept(atoms, names) if names else atoms
            size, best = self.largest(atoms, rr[0], rr[1], k=k, way=way); vote = size      # the page's size is voted on without the kept strings: a name does not make a whole page smaller
            if size and held is not atoms:
                s2, b2 = self.largest(held, rr[0], rr[1], k=k, way=way)
                if s2: free, atoms, size, best = atoms, held, s2, b2
            if not size: rec["why_not"] = "the text does not go into the balloon at 8 px"; continue
            items.append((blk, rec, atoms, rr[0], rr[1], filled, ox, oy, size, best, k, way, free, names, shown, vote, paint, paper, pen))
        page_px = self.page_size(items, page.get("height") or img.height) if items else 0
        canvas = img.copy(); draw = ImageDraw.Draw(canvas)
        lettered = np.zeros((img.height, img.width), bool); over = 0       # where new lettering stands, and how many of its pixels a later block painted or lettered over: 0 on a page set right
        for blk, rec, atoms, L, R, filled, ox, oy, own, best, k, way, free, names, shown, vote, paint, paper, pen in items:
            least = self.least(page_px, page.get("height") or img.height, way)
            size, got = self.settle(atoms, L, R, own, best, page_px, k, way, least)
            if free is not None and (got is None or size < least):       # a kept string makes the block too small: it is given up for this block
                own2, best2 = self.largest(free, L, R, k=k, way=way); s2, g2 = self.settle(free, L, R, own2, best2, page_px, k, way, least)
                if g2 is not None and (got is None or s2 > size): size, got, own = s2, g2, own2
            if got is None: rec["why_not"] = "the text does not go into the balloon"; continue
            _, lines, y0, spans, nh, inside = got[:6]
            over += int((lettered[oy:oy + paint.shape[0], ox:ox + paint.shape[1]] & paint).sum())     # this block's paint over an earlier block's new lettering
            canvas.paste(paper, (ox, oy), Image.fromarray(paint.astype(np.uint8) * 255))
            wx0, wy0, wx1, wy1 = max(0, ox - 200), max(0, oy - 200), min(canvas.width, ox + paint.shape[1] + 200), min(canvas.height, oy + paint.shape[0] + 200)
            before = np.asarray(canvas.crop((wx0, wy0, wx1, wy1))).astype(np.int16)
            if way is None:
                f = self.font(size, k); lh = int(round(LEAD * size))
                for i, line in enumerate(lines):
                    l, r = spans[i]; w = f.getlength(line)
                    draw.text((ox + (l + r) / 2 - w / 2, oy + y0 + i * lh), line, font=f, fill=pen)
            elif not way.vertical:
                f = self.font(size, k, plain=True); lh = int(round(way.lead * size))
                for i, line in enumerate(lines):                  # each line's ink centred in its span, the em box in the middle of the line's pitch
                    l, r = spans[i]; pieces, a, b = self.pieces(line, size, k)
                    for x, piece in pieces: draw.text((ox + (l + r) / 2 - (a + b) / 2 + x, oy + y0 + i * lh + (lh - size) / 2.0 + vertical.EM_TOP * size), piece, font=f, fill=pen, anchor="ls")
            else:
                f = self.font(size, k, plain=True); lh = int(round(way.lead * size)); top = spans[0][0]
                down = max(0.0, min(r - top - vertical.length(line, f, size, way.region) for (_, r), line in zip(spans, lines))) / 2.0
                for i, line in enumerate(lines):                  # columns from the right, hung from one line, the whole block in the middle of the room
                    vertical.draw(canvas, f, size, line, ox + filled.shape[1] - (y0 + i * lh + lh / 2.0), oy + top + down, pen, way.region)
                rec["vertical"] = True
            if way is not None: rec["inside_phrase"] = inside
            broken = [n for n in names if sum(l.count(n) for l in lines) < shown.count(n)]
            if broken: rec["keep_broken"] = broken
            mine = np.abs(np.asarray(canvas.crop((wx0, wy0, wx1, wy1))).astype(np.int16) - before).max(axis=2) > 24      # what this block's lettering changed
            over += int((lettered[wy0:wy1, wx0:wx1] & mine).sum()); lettered[wy0:wy1, wx0:wx1] |= mine
            rec.update(set=True, px=size, page_px=page_px, own_size=size != page_px, lines=lines, hyphenations=nh, largest_px=own)
        return canvas, {"page_px": page_px, "overlap_px": over, "blocks": report}
