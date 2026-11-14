"""The lines of a block of lettering, for the readers that read one LINE at a time (reader.LineReader: every language
but Japanese). A block is cut by the text-line detector of the manifest (PP-OCRv5 mobile, ONNX Runtime on the CPU) run on
the block's crop; cutting by the ink's row profile was measured beside it and left out (English Pepper&Carrot, 20
pages: 9.4 % of the characters wrong against 0.4 %, because a neighbour's lettering or a piece of outline inside the
crop spoils the profile).

Finder   the crop is brought to a size where its letters stand about 36 px high (never enlarged more than 4 times, the
         long side 1280 px at most, both sides multiples of 32), BGR, ImageNet mean and deviation; the output is a
         probability map; a box is the bounding rectangle of a part above 0.3 whose mean probability is 0.6 or more,
         grown by PaddleOCR's own distance (area x 1.5 / perimeter). A line that is level (within 3 degrees) is
         its upright rectangle [x1, y1, x2, y2]; a line that slants (hand lettering often rises to the right) is its
         smallest turned rectangle, given as a fifth item, the four corners, and `cut` straightens it for the reader
         (on ten slanted crops of a 1940s page the upright rectangles lost a line or more in every one). Specks go: a
         box lying 0.7 or more inside a larger one, or under 0.4 of the median box's height (they were read as "-"
         and ":" between the lines).
rows     boxes in reading order as lines: rows from the top (a box joins a row while its middle lies within half the
         line's thickness of the row's first box), left to right inside a row.
columns  a block is lettered in COLUMNS when its high boxes (1.3 times as high as wide, or more) cover more area than
         its wide ones (1.3 times as wide as high); when it has neither (every box one glyph), when more of its boxes
         stand above one another than beside one another. Its boxes are then gathered into columns, right to left,
         each from its top; a high box is a column of several glyphs, any other box is one glyph of a column.
turned   a column as the line reader takes it: turned a quarter turn counter-clockwise, its top to the left
         (PaddleOCR's own pipeline does the same with a line at least 1.5 times as high as wide). Laying a column's
         glyphs side by side instead was measured and is far worse (drawn Chinese set: 48 % of characters wrong
         against 11 %). A box of one glyph is read as it stands.
paper_and_ink   a crop's paper colour (its most common colour, in 16-step bins) and its ink (pixels whose largest
         channel difference from the paper is above Otsu's threshold, 48 at least).
in_columns      whether an ink mask runs in columns, with no detector (the Japanese path, which loads none): one glyph
         wide and at least two high is a column; one glyph high and two wide is a line; else the direction with the
         wider empty gap between its runs (columns stand further apart than the glyphs inside them)."""
import cv2
import numpy as np
import onnxruntime as ort
from PIL import Image

def bgr(image):
    return np.ascontiguousarray(np.asarray(image.convert("RGB"))[:, :, ::-1])

def paper_and_ink(rgb):
    """(paper colour [r, g, b], ink mask) of a crop given as an HxWx3 array."""
    q = rgb.astype(np.int32) // 16; key = (q[..., 0] << 8) | (q[..., 1] << 4) | q[..., 2]
    vals, counts = np.unique(key, return_counts=True); k = vals[counts.argmax()]
    paper = np.median(rgb[key == k].reshape(-1, 3), axis=0)
    dist = np.abs(rgb.astype(np.int32) - paper.astype(np.int32)).max(axis=2).astype(np.uint8)
    thr, _ = cv2.threshold(dist, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    return [int(round(v)) for v in paper], dist > max(48, thr)

def glyph_px(ink):
    """The glyph size of an ink mask: the 85th percentile of the larger side of its parts (6 px of area at least)."""
    n, _, st, _ = cv2.connectedComponentsWithStats(ink.astype(np.uint8), connectivity=8)
    big = np.maximum(st[1:, cv2.CC_STAT_WIDTH], st[1:, cv2.CC_STAT_HEIGHT]); big = big[st[1:, cv2.CC_STAT_AREA] >= 6]
    return float(np.percentile(big, 85)) if len(big) else None

def _widest_gap(profile):
    on = np.nonzero(profile)[0]
    if len(on) < 2: return 0, 0
    d = np.diff(on) - 1
    return int(d.max()), int(on[-1] - on[0] + 1)

def in_columns(ink):
    """True when the ink of a block runs in columns (vertical lettering), False when in lines."""
    ys, xs = np.nonzero(ink)
    if len(xs) < 20: return False
    w, h = int(xs.max() - xs.min() + 1), int(ys.max() - ys.min() + 1); G = glyph_px(ink) or 0.0
    if G >= 6:
        if w < 1.5 * G and h >= 2.0 * G: return True
        if h < 1.5 * G and w >= 2.0 * G: return False
    gx, _ = _widest_gap(ink.sum(axis=0)); gy, _ = _widest_gap(ink.sum(axis=1))
    if gx == gy: return h > 1.2 * w
    return gx > gy

def corners(b):
    """The four corners of a line box: its turned rectangle when it has one, else its upright one."""
    if len(b) > 4: return np.array(b[4], dtype=np.float32)
    return np.array([[b[0], b[1]], [b[2], b[1]], [b[2], b[3]], [b[0], b[3]]], dtype=np.float32)

def thick(b):
    """A line box's thickness (its height when it is level; the short side of its turned rectangle)."""
    if len(b) > 4:
        q = corners(b); return float(min(np.linalg.norm(q[0] - q[1]), np.linalg.norm(q[1] - q[2])))
    return float(b[3] - b[1])

def _area(b):
    return float(cv2.contourArea(corners(b)))

def _shared(a, b):
    if len(a) == 4 and len(b) == 4:
        return max(0.0, min(a[2], b[2]) - max(a[0], b[0])) * max(0.0, min(a[3], b[3]) - max(a[1], b[1]))
    return float(cv2.intersectConvexConvex(corners(a), corners(b))[0])

def clean(boxes):
    """The boxes without the specks."""
    if len(boxes) < 2: return boxes
    med = float(np.median([thick(b) for b in boxes])); keep = []
    for i, b in enumerate(boxes):
        if thick(b) < 0.4 * med: continue
        inside = False
        for j, c in enumerate(boxes):
            if i == j or _area(c) <= _area(b): continue
            if _shared(b, c) >= 0.7 * _area(b): inside = True; break
        if not inside: keep.append(b)
    return keep

def slant(b):
    """The angle of a line box's long side, in radians (0 for a level box; negative when the line rises to the right)."""
    if len(b) <= 4: return 0.0
    q = corners(b); q = q[np.argsort(q[:, 0])]
    tl = min(q[:2], key=lambda p: p[1]); tr = min(q[2:], key=lambda p: p[1])
    return float(np.arctan2(tr[1] - tl[1], tr[0] - tl[0]))

def rows(boxes):
    """Line boxes in reading order: rows from the top, left to right inside a row. Where lines slant, the block is
    first turned level in thought (by the median slant of its slanted boxes), so that the two halves of one rising
    line stay one row."""
    leaning = [slant(b) for b in boxes if len(b) > 4]; a = float(np.median(leaning)) if leaning else 0.0
    ca, sa = np.cos(a), np.sin(a)
    def level(b):
        x, y = corners(b).mean(axis=0)
        return x * ca + y * sa, -x * sa + y * ca
    at = [level(b) for b in boxes]
    idx = sorted(range(len(boxes)), key=lambda i: at[i][1]); out = []
    for i in idx:
        if out and abs(at[i][1] - at[out[-1][0]][1]) <= 0.5 * thick(boxes[out[-1][0]]): out[-1].append(i)
        else: out.append([i])
    return [boxes[i] for r in out for i in sorted(r, key=lambda i: at[i][0])]

def is_high(b):
    return b[3] - b[1] >= 1.3 * (b[2] - b[0])

def columns(boxes):
    """The boxes of a block lettered in columns, in reading order (columns right to left, each from its top); [] when
    the block is lettered in lines."""
    area = lambda b: max(0.0, b[2] - b[0]) * max(0.0, b[3] - b[1])
    high = sum(area(b) for b in boxes if is_high(b)); wide = sum(area(b) for b in boxes if b[2] - b[0] >= 1.3 * (b[3] - b[1]))      # by the upright boxes
    if high == 0 and wide == 0:                                   # every box is one glyph: above one another, or side by side?
        above = beside = 0
        for i, a in enumerate(boxes):
            for b in boxes[i + 1:]:
                ox = min(a[2], b[2]) - max(a[0], b[0]); oy = min(a[3], b[3]) - max(a[1], b[1])
                above += ox >= 0.5 * min(a[2] - a[0], b[2] - b[0]) and oy < 0.5 * min(a[3] - a[1], b[3] - b[1])
                beside += oy >= 0.5 * min(a[3] - a[1], b[3] - b[1]) and ox < 0.5 * min(a[2] - a[0], b[2] - b[0])
        if above <= beside: return []
    elif high <= wide: return []
    cols = []
    for b in sorted(boxes, key=lambda b: -(b[0] + b[2]) / 2):
        mid = (b[0] + b[2]) / 2
        for c in cols:
            if c[0][0] <= mid <= c[0][2]: c.append(b); break
        else: cols.append([b])
    return [b for c in cols for b in sorted(c, key=lambda b: b[1])]

def cut(crop, box):
    """The line's picture: the box cut from the crop; a slanted line is straightened (its turned rectangle laid level)."""
    w, h = crop.size
    if len(box) > 4:
        q = corners(box); q = q[np.argsort(q[:, 0])]                 # the two left corners, then the two right ones
        tl, bl = sorted(q[:2], key=lambda p: p[1]); tr, br = sorted(q[2:], key=lambda p: p[1])
        lw = int(round(max(np.linalg.norm(tr - tl), np.linalg.norm(br - bl)))); lh = int(round(max(np.linalg.norm(bl - tl), np.linalg.norm(br - tr))))
        if lw >= 2 and lh >= 2:
            m = cv2.getPerspectiveTransform(np.array([tl, tr, br, bl], dtype=np.float32), np.array([[0, 0], [lw, 0], [lw, lh], [0, lh]], dtype=np.float32))
            return Image.fromarray(cv2.warpPerspective(np.asarray(crop.convert("RGB")), m, (lw, lh), flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE))
    return crop.crop([int(max(0, box[0])), int(max(0, box[1])), int(min(w, np.ceil(box[2]))), int(min(h, np.ceil(box[3])))])

def turned(crop, box):
    """A box of a block lettered in columns as the line reader takes it: a column turned a quarter turn, one glyph as it is."""
    return cut(crop, box[:4]).transpose(Image.ROTATE_90) if is_high(box) else cut(crop, box[:4])

class Finder:
    MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32); DEV = np.array([0.229, 0.224, 0.225], dtype=np.float32)

    def __init__(self, path, threads=4, thresh=0.3, box_thresh=0.6, unclip=1.5):
        so = ort.SessionOptions(); so.intra_op_num_threads = int(threads); so.inter_op_num_threads = 1; so.log_severity_level = 3
        self.sess = ort.InferenceSession(path, sess_options=so, providers=["CPUExecutionProvider"]); self.inp = self.sess.get_inputs()[0].name
        self.thresh, self.box_thresh, self.unclip = thresh, box_thresh, unclip

    def factor(self, crop):
        """How much the crop is enlarged: its letters to about 36 px, 1 to 4 times, the long side 1280 px at most."""
        _, ink = paper_and_ink(np.asarray(crop)); g = glyph_px(ink)
        f = 3.0 if not g else min(4.0, max(1.0, 36.0 / g))
        return min(f, 1280.0 / max(crop.size))

    def __call__(self, crop):
        """The text lines of a crop (a PIL image): [[x1, y1, x2, y2]] in the crop's pixels, specks dropped, unordered."""
        crop = crop.convert("RGB"); w, h = crop.size; f = self.factor(crop)
        nw, nh = max(32, int(round(w * f / 32.0)) * 32), max(32, int(round(h * f / 32.0)) * 32)
        x = (bgr(crop.resize((nw, nh), Image.LANCZOS)).astype(np.float32) / 255.0 - self.MEAN) / self.DEV
        prob = self.sess.run(None, {self.inp: x.transpose(2, 0, 1)[None]})[0][0, 0]
        cs, _ = cv2.findContours((prob > self.thresh).astype(np.uint8), cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE); out = []
        sx, sy = w / float(nw), h / float(nh)
        for c in cs:
            x0, y0, bw, bh = cv2.boundingRect(c)
            if min(bw, bh) < 3: continue
            m = np.zeros((bh, bw), dtype=np.uint8); cv2.fillPoly(m, [c - np.array([x0, y0])], 1)
            if not m.any() or float(prob[y0:y0 + bh, x0:x0 + bw][m > 0].mean()) < self.box_thresh: continue
            (cx, cy), (rw, rh), ang = cv2.minAreaRect(c); lean = abs(((ang + 45.0) % 90.0) - 45.0)        # degrees off level or upright
            if lean < 3.0 or min(rw, rh) < 3 or max(rw, rh) < 3.0 * min(rw, rh):                           # level, or too short to say: the upright rectangle
                d = bw * bh * self.unclip / (2.0 * (bw + bh))
                out.append([max(0.0, (x0 - d) * sx), max(0.0, (y0 - d) * sy), min(float(w), (x0 + bw + d) * sx), min(float(h), (y0 + bh + d) * sy)])
                continue
            d = rw * rh * self.unclip / (2.0 * (rw + rh))
            q = cv2.boxPoints(((cx, cy), (rw + 2 * d, rh + 2 * d), ang)) * np.array([sx, sy], dtype=np.float32)
            q[:, 0] = np.clip(q[:, 0], 0, w); q[:, 1] = np.clip(q[:, 1], 0, h)
            out.append([float(q[:, 0].min()), float(q[:, 1].min()), float(q[:, 0].max()), float(q[:, 1].max()), [[float(x), float(y)] for x, y in q]])
        return clean(out)
