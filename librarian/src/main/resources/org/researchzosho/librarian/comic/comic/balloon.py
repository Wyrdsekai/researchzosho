"""The balloon's inside around a text box, taken from the page itself: the area of the balloon's own colour that holds
the text, with its holes (the lettering) filled. Boxes are in page pixels; masks are over a region with origin (x0, y0).

paper   the balloon's own colour: the most common colour inside the text box (16-step bins, the median of the
        fullest bin), where most pixels are paper; see paper_of for the box where they are not. Without a colour page
        (a gray array alone) the paper is white.
joined  the share of the lettering that runs into the outline: of the dark pixels inside the text box, those the paper
        does not enclose. With hull=True only the dark pixels inside the convex hull of the balloon's paper count: it
        keeps out the art beside a balloon when the detector's text box is wider than the balloon (without it 32 of
        207 balloons on 21 American pages of the 1940s counted as drawn sound words). read.py asks for the hull for
        every language but Japanese, whose pages were measured without it: on the 30 Black Jack pages the hull changes
        one block, a phone's ring in outlined letters, from a sound word to speech.
inside  white paper (gray 230 or more and its channels within 24 of each other): the pixels with gray above 190, the
        rule every black-and-white page was measured with. Any other paper (a coloured balloon, a caption box, a dark
        balloon with light lettering): the pixels with every channel within 48 of the paper.
dark    the lettering and the outline: gray under 128 on white paper; else a channel 96 or more from the paper.
ink     the lettering's colour: the median colour of the dark pixels inside the filled inside and the text box; black
        on a light paper and white on a dark one when there are too few of them to say.
Where the paper runs out of the search region (a balloon joined to its neighbour, or open to a panel of its colour)
thin leaks are cut by an opening and, if it still runs out, the area is clipped to the detector's balloon box."""
import cv2
import numpy as np

def fill_holes(mask):
    m = np.pad(mask.astype(np.uint8), 1)
    cv2.floodFill(m, None, (0, 0), 2)
    return m[1:-1, 1:-1] != 2

def pick(white, tx0, ty0, tx1, ty1):
    n, lab = cv2.connectedComponents(white.astype(np.uint8), connectivity=4)
    inside = lab[max(0, ty0):ty1, max(0, tx0):tx1]
    cnt = np.bincount(inside.ravel(), minlength=n); cnt[0] = 0
    return (lab == int(cnt.argmax())) if cnt.max() > 0 else None

def _mode(px):
    """The median colour of the fullest 16-step bin of some pixels (Nx3)."""
    q = px.astype(np.int32) // 16; key = (q[:, 0] << 8) | (q[:, 1] << 4) | q[:, 2]
    vals, counts = np.unique(key, return_counts=True)
    return np.median(px[key == vals[counts.argmax()]], axis=0)

def paper_of(rgb, tb):
    """The colour of the paper inside a text box of an HxWx3 page: [r, g, b]. It is the most common colour of the box;
    where under half of the box is within 48 of that colour (heavy lettering in a tight box on a paper that is not
    flat: the ink is then the one colour that repeats), the most common colour of the rest is tried as well and the
    one that holds more of the box is the paper."""
    return papers_of(rgb, tb)[0]

def papers_of(rgb, tb):
    """(the paper paper_of gives, the other of the two colours or None). The other is given only where the two hold
    nearly as much of the box (the smaller three quarters of the larger or more): there the share alone does not say
    which is the paper, and `interior` asks which of them closes round the lettering."""
    H, W = rgb.shape[:2]
    reg = rgb[max(0, int(tb[1])):min(H, int(np.ceil(tb[3]))), max(0, int(tb[0])):min(W, int(np.ceil(tb[2])))]
    if reg.size == 0: return [255, 255, 255], None
    px = reg.reshape(-1, 3); c1 = _mode(px); other = None
    near1 = np.abs(px.astype(np.int16) - c1.astype(np.int16)).max(axis=1) < 48
    if near1.mean() < 0.5 and (~near1).sum() >= 20:
        c2 = _mode(px[~near1]); near2 = np.abs(px.astype(np.int16) - c2.astype(np.int16)).max(axis=1) < 48
        n1, n2 = int(near1.sum()), int(near2.sum())
        if n2 > n1: c1, c2 = c2, c1
        if min(n1, n2) >= 0.75 * max(n1, n2): other = [int(round(v)) for v in c2]
    return [int(round(v)) for v in c1], other

def gray_of(c):
    return 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]

def is_white(paper):
    return paper is None or (gray_of(paper) >= 230 and max(paper) - min(paper) < 24)

def near_and_dark(gray_reg, rgb_reg, paper):
    """(the pixels of the paper's colour, the pixels of lettering and outline) over a region."""
    if rgb_reg is None or is_white(paper): return gray_reg > 190, gray_reg < 128
    dist = np.abs(rgb_reg.astype(np.int16) - np.array(paper, dtype=np.int16)).max(axis=2)
    return dist < 48, dist >= 96

def interior(gray, tb, bubbles, rgb=None, hull=False):
    """dict: filled (the inside with holes filled), white (its part of the paper's colour), origin, how, joined (share of
    the dark pixels in the text box that are NOT enclosed by the paper: lettering whose strokes join the outline),
    closed, paper and ink ([r, g, b] each), or None. rgb: the page as an HxWx3 array; without it the page is taken as
    black on white. hull: see `joined` above.
    The paper is the text box's commonest colour (paper_of). Where a second colour holds nearly as much of the box
    (papers_of) and the first gives no closed inside, the second is tried, and is the paper when its inside is closed,
    is not clipped, and encloses lettering: a caption strip with heavy lettering and its own thick border in the text
    box, where border and lettering together outweigh the paper (seen on a real page: the strip was painted in its
    ink's colour)."""
    if rgb is None: return _inside(gray, tb, bubbles, None, hull, None)
    first, other = papers_of(rgb, tb)
    got = _inside(gray, tb, bubbles, rgb, hull, first)
    if other is not None and (got is None or not got["closed"]):
        alt = _inside(gray, tb, bubbles, rgb, hull, other)
        if alt is not None and alt["closed"] and alt["joined"] is not None and alt["joined"] < 0.9: return alt
    return got

def _inside(gray, tb, bubbles, rgb, hull, paper):
    H, Wd = gray.shape
    cx, cy = (tb[0] + tb[2]) / 2, (tb[1] + tb[3]) / 2
    holds = [b for b in bubbles if b[0] <= cx <= b[2] and b[1] <= cy <= b[3]]
    if holds: bb = min(holds, key=lambda b: (b[2] - b[0]) * (b[3] - b[1])); how = 'balloon'
    else:
        w, h = tb[2] - tb[0], tb[3] - tb[1]; bb = [tb[0] - .15 * w, tb[1] - .1 * h, tb[2] + .15 * w, tb[3] + .1 * h]; how = 'no balloon box'
    pad = int(max(24, 0.2 * min(bb[2] - bb[0], bb[3] - bb[1])))
    ub = [min(bb[0], tb[0]), min(bb[1], tb[1]), max(bb[2], tb[2]), max(bb[3], tb[3])]       # the balloon box and the block's text box together: the lettering is in the balloon, whatever the detector's balloon box says
    x0, y0 = max(0, int(ub[0]) - pad), max(0, int(ub[1]) - pad); x1, y1 = min(Wd, int(ub[2]) + pad), min(H, int(ub[3]) + pad)
    reg = gray[y0:y1, x0:x1]
    creg = rgb[y0:y1, x0:x1] if rgb is not None else None
    white, dark_all = near_and_dark(reg, creg, paper)
    tx0, ty0, tx1, ty1 = [int(round(v)) for v in (tb[0] - x0, tb[1] - y0, tb[2] - x0, tb[3] - y0)]
    comp = pick(white, tx0, ty0, tx1, ty1)
    if comp is None: return None
    def leak(c):
        edge = c[0, :].sum() + c[-1, :].sum() + c[:, 0].sum() + c[:, -1].sum()
        return edge > 0.03 * 2 * (c.shape[0] + c.shape[1])
    dark = dark_all[max(0, ty0):ty1, max(0, tx0):tx1]
    closed = not leak(comp)
    f0 = fill_holes(comp)
    enclosed = (f0 & ~comp)[max(0, ty0):ty1, max(0, tx0):tx1]
    joined = None
    if closed and hull:                                           # only the dark pixels inside the balloon count: the hull of its paper
        inside = np.zeros(comp.shape, dtype=np.uint8); cv2.fillConvexPoly(inside, cv2.convexHull(cv2.findNonZero(comp.astype(np.uint8))), 1)
        dark = dark & (inside[max(0, ty0):ty1, max(0, tx0):tx1] > 0)
        joined = 1.0 - float((enclosed & dark).sum()) / max(1, int(dark.sum())) if dark.sum() >= 12 else 0.0
    elif closed:
        joined = 1.0 - float((enclosed & dark).sum()) / max(1, int(dark.sum()))
    if not closed:
        r = max(3, int(0.03 * min(bb[2] - bb[0], bb[3] - bb[1])))
        k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1, 2 * r + 1))
        w2 = comp.copy(); w2[max(0, ty0 + 2):ty1 - 2, max(0, tx0 + 2):tx1 - 2] = True
        opened = cv2.morphologyEx(w2.astype(np.uint8), cv2.MORPH_OPEN, k) > 0
        c2 = pick(opened, tx0, ty0, tx1, ty1)
        if c2 is None: c2 = w2
        if not leak(c2): how += ', leak cut'
        else:
            clip = np.zeros_like(comp)
            clip[max(0, int(ub[1]) - y0 - 4):int(ub[3]) - y0 + 4, max(0, int(ub[0]) - x0 - 4):int(ub[2]) - x0 + 4] = True     # never short of the block's own text box: a first or last line outside the inside would stay unpainted under its translation
            c2 = c2 & clip; how += ', open: clipped to the box'
        filled = fill_holes(c2); comp = filled & white
    else:
        filled = f0
    if filled.sum() < 0.3 * (tb[2] - tb[0]) * (tb[3] - tb[1]): return None
    if paper is None: paper = [255, 255, 255]
    ink = [0, 0, 0] if gray_of(paper) >= 128 else [255, 255, 255]
    if creg is not None:
        box = np.zeros_like(filled); box[max(0, ty0):ty1, max(0, tx0):tx1] = True
        px = creg[filled & dark_all & box]
        if len(px) >= 20: ink = [int(round(v)) for v in np.median(px, axis=0)]
    return {"filled": filled, "white": comp, "origin": (x0, y0), "how": how, "joined": joined, "closed": closed, "paper": paper, "ink": ink}


def open_ground(gray, rgb, tb, keep_out, glyph=None):
    """The free flat ground around lettering that stands outside any balloon (a web comic's narration between its
    panels), in the shape `interior` gives a balloon's inside, or None when the lettering does not stand on flat
    ground. keep_out: the boxes the inside may not enter (the page's other text boxes and its balloons).
      ground   the paper is the text box's most common colour (paper_of); a pixel is ground when every channel is
               within 48 of it, and lettering or art when a channel is 96 or more from it.
      flat     half of the text box or more is ground, and 0.8 of that ground is within 20 of the paper (lettering
               set over a glow or a light picture is not on flat ground); of the band around the lettering's own extent (a third of a
               glyph wide, 4 px at least; the parts of it inside a keep-out box left out, so that the next line of
               the same narration does not count as art) 0.92 or more is ground; and 0.9 of the band's ground is
               within 20 of the paper (a gradient, a tone or a texture is not flat).
      its own lettering   the dark parts that touch the text box; one that runs out of the search window is a panel's
               line or art, and the block is then not on open ground.
      inside   a rectangle: the text box and its own lettering, then each side moved outwards row by row (column by
               column) while the whole row is ground (every pixel ground, 0.97 of them within 20 of the paper, so
               that a faint ring or glow stops it as a line does), it enters no keep-out box
               and it has not gone further than a quarter of the box's width (half its height), three glyphs (two)
               at most. So it holds the old lettering and ground, and nothing else."""
    H, W = gray.shape
    x0, y0, x1, y1 = max(0, int(tb[0])), max(0, int(tb[1])), min(W, int(np.ceil(tb[2]))), min(H, int(np.ceil(tb[3])))
    w, h = x1 - x0, y1 - y0
    if w < 4 or h < 4: return None
    g = float(glyph) if glyph else max(8.0, 0.5 * min(w, h))
    mx = int(max(8, min(0.25 * w, 3 * g))); my = int(max(8, min(0.5 * h, 2 * g))); band = int(max(4, round(g / 3.0)))
    wx0, wy0, wx1, wy1 = max(0, x0 - mx - band), max(0, y0 - my - band), min(W, x1 + mx + band), min(H, y1 + my + band)
    paper = paper_of(rgb, tb)
    dist = np.abs(rgb[wy0:wy1, wx0:wx1].astype(np.int16) - np.array(paper, dtype=np.int16)).max(axis=2)
    near, far = dist < 48, dist >= 96
    bx0, by0, bx1, by1 = x0 - wx0, y0 - wy0, x1 - wx0, y1 - wy0
    inbox = dist[by0:by1, bx0:bx1]
    if (inbox < 48).mean() < 0.5 or (inbox[inbox < 48] < 20).mean() < 0.8: return None      # the lettering stands on a glow, a tone or a picture
    n, lab = cv2.connectedComponents(far.astype(np.uint8), connectivity=8)
    own = np.unique(lab[by0:by1, bx0:bx1]); own = own[own > 0]
    if len(own) == 0: return None
    mine = np.isin(lab, own); ys, xs = np.nonzero(mine)
    if xs.min() == 0 or ys.min() == 0 or xs.max() == mine.shape[1] - 1 or ys.max() == mine.shape[0] - 1: return None      # it runs out of the window: a line of the art
    rx0, ry0, rx1, ry1 = min(bx0, int(xs.min())), min(by0, int(ys.min())), max(bx1, int(xs.max()) + 1), max(by1, int(ys.max()) + 1)
    a0, b0, a1, b1 = max(0, rx0 - band), max(0, ry0 - band), min(near.shape[1], rx1 + band), min(near.shape[0], ry1 + band)
    ring = np.ones((b1 - b0, a1 - a0), dtype=bool); ring[ry0 - b0:ry1 - b0, rx0 - a0:rx1 - a0] = False
    for k in keep_out:                                             # a neighbour's lettering in the band is not art: it is left out of the band
        kx0, ky0, kx1, ky1 = int(k[0]) - 2 - wx0 - a0, int(k[1]) - 2 - wy0 - b0, int(np.ceil(k[2])) + 2 - wx0 - a0, int(np.ceil(k[3])) + 2 - wy0 - b0
        ring[max(0, ky0):max(0, ky1), max(0, kx0):max(0, kx1)] = False
    if ring.sum() < 20 or near[b0:b1, a0:a1][ring].mean() < 0.92: return None
    ground = dist[b0:b1, a0:a1][ring & near[b0:b1, a0:a1]]
    if (ground < 20).mean() < 0.9: return None
    def free(ax0, ay0, ax1, ay1):
        if ax0 < 0 or ay0 < 0 or ax1 > near.shape[1] or ay1 > near.shape[0]: return False
        if far[ay0:ay1, ax0:ax1].any() or not near[ay0:ay1, ax0:ax1].all() or (dist[ay0:ay1, ax0:ax1] < 20).mean() < 0.97: return False
        px0, py0, px1, py1 = ax0 + wx0, ay0 + wy0, ax1 + wx0, ay1 + wy0
        return not any(min(px1, k[2]) > max(px0, k[0]) and min(py1, k[3]) > max(py0, k[1]) for k in keep_out)
    lim = {"l": rx0 - mx, "r": rx1 + mx, "t": ry0 - my, "b": ry1 + my}; go = {"l": True, "r": True, "t": True, "b": True}
    while any(go.values()):
        if go["l"]: go["l"] = rx0 - 1 >= lim["l"] and free(rx0 - 1, ry0, rx0, ry1); rx0 -= go["l"]
        if go["r"]: go["r"] = rx1 + 1 <= lim["r"] and free(rx1, ry0, rx1 + 1, ry1); rx1 += go["r"]
        if go["t"]: go["t"] = ry0 - 1 >= lim["t"] and free(rx0, ry0 - 1, rx1, ry0); ry0 -= go["t"]
        if go["b"]: go["b"] = ry1 + 1 <= lim["b"] and free(rx0, ry1, rx1, ry1 + 1); ry1 += go["b"]
    if (far[ry0:ry1, rx0:rx1] & ~mine[ry0:ry1, rx0:rx1]).any(): return None
    filled = np.ones((ry1 - ry0, rx1 - rx0), dtype=bool)
    px = rgb[wy0 + ry0:wy0 + ry1, wx0 + rx0:wx0 + rx1][far[ry0:ry1, rx0:rx1]]
    ink = [int(round(v)) for v in np.median(px, axis=0)] if len(px) >= 20 else ([0, 0, 0] if gray_of(paper) >= 128 else [255, 255, 255])
    return {"filled": filled, "white": near[ry0:ry1, rx0:rx1].copy(), "origin": (wx0 + rx0, wy0 + ry0), "how": "open ground", "joined": None, "closed": True, "paper": paper, "ink": ink}

def outline(found):
    """The inside as a polygon in page pixels (its outer contour, lightly simplified): [[x, y], ...]."""
    f = found["filled"].astype(np.uint8); x0, y0 = found["origin"]
    cs, _ = cv2.findContours(f, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not cs: return []
    c = max(cs, key=cv2.contourArea)
    c = cv2.approxPolyDP(c, 0.75, True)
    return [[int(p[0][0]) + x0, int(p[0][1]) + y0] for p in c]

def mask_of(polygon, shape):
    """A polygon back to a mask: (mask over its bounding region, region origin)."""
    pts = np.array(polygon, dtype=np.int32)
    x0, y0 = int(max(0, pts[:, 0].min() - 2)), int(max(0, pts[:, 1].min() - 2))
    x1, y1 = int(min(shape[1], pts[:, 0].max() + 3)), int(min(shape[0], pts[:, 1].max() + 3))
    m = np.zeros((y1 - y0, x1 - x0), dtype=np.uint8)
    cv2.fillPoly(m, [pts - np.array([x0, y0], dtype=np.int32)], 1)
    return m.astype(bool), (x0, y0)
