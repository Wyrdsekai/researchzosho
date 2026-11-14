"""Boxes and reading order. A box is [x1, y1, x2, y2] in pixels of the page."""

def area(b):
    return max(0.0, b[2] - b[0]) * max(0.0, b[3] - b[1])

def iou(a, b):
    ix = max(0.0, min(a[2], b[2]) - max(a[0], b[0])); iy = max(0.0, min(a[3], b[3]) - max(a[1], b[1]))
    u = area(a) + area(b) - ix * iy
    return ix * iy / u if u > 0 else 0.0

def crop_box(box, width, height, margin_frac=0.06, margin_min=4):
    """The box grown by a small margin (6 % of its shorter side, 4 px at least), kept inside the page."""
    m = max(margin_min, margin_frac * min(box[2] - box[0], box[3] - box[1]))
    return [int(max(0, box[0] - m)), int(max(0, box[1] - m)), int(min(width, box[2] + m)), int(min(height, box[3] + m))]

def rtl_of(language, vertical=False):
    """Whether a comic in this language reads right to left: Japanese does; Chinese does when it is lettered in
    columns (the traditional layout, a page laid out like manga); everything else reads left to right."""
    code = (language or "ja").lower().replace("_", "-").split("-")[0]
    return code == "ja" or (code == "zh" and bool(vertical))

def reading_order(boxes, rtl=True, stacked=False):
    """Rows from the top; inside a row right to left (rtl, the Japanese page) or left to right. A box joins the
    current row while its top edge lies above the vertical middle of the row's first box. stacked: two boxes of a row
    that stand one above the other (they share half the width of the narrower, or more) are read from the top, not by
    their sides (every language but Japanese, whose pages were measured without it). Returns indexes into boxes."""
    if not rtl:
        return reading_order([[-b[2], b[1], -b[0], b[3]] for b in boxes], rtl=True, stacked=stacked)       # the mirrored page, read right to left
    idx = sorted(range(len(boxes)), key=lambda i: (boxes[i][1], -boxes[i][2]))
    rows, cur, mid = [], [], None
    for i in idx:
        b = boxes[i]
        if cur and b[1] > mid:
            rows.append(cur); cur = []
        if not cur: mid = (b[1] + b[3]) / 2.0
        cur.append(i)
    if cur: rows.append(cur)
    out = []
    for r in rows:
        r = sorted(r, key=lambda i: -boxes[i][2])
        if stacked:
            moved = True
            while moved:
                moved = False
                for k in range(len(r) - 1):
                    a, b = boxes[r[k]], boxes[r[k + 1]]
                    if min(a[2], b[2]) - max(a[0], b[0]) >= 0.5 * min(a[2] - a[0], b[2] - b[0]) and b[1] < a[1]:
                        r[k], r[k + 1] = r[k + 1], r[k]; moved = True
        out.extend(r)
    return out

def drop_doubles(dets):
    """Of two text boxes that overlap with IoU >= 0.5, or one lying >= 80 % inside the other, the lower-scored goes."""
    keep = []
    for b in sorted(dets, key=lambda d: -d["score"]):
        drop = False
        for k in keep:
            a, c = b["box"], k["box"]
            ix = max(0, min(a[2], c[2]) - max(a[0], c[0])); iy = max(0, min(a[3], c[3]) - max(a[1], c[1]))
            if iou(a, c) >= 0.5 or ix * iy >= 0.8 * min(area(a), area(c)):
                drop = True; break
        if not drop: keep.append(b)
    return keep
