"""A web comic's strip: one very tall image an episode (800 x 12,000 px is ordinary). The page detector squeezes what it
is given to 640 x 640, so a strip is cut into pieces of a page's shape first, each piece is read and set as a page, and
the pieces are joined again.

strip    an image more than STRIP (2.2) times as high as wide. Anything else is a page and is not cut.
         Measured on three strips put together from a comic's tiers of panels (1200 px wide, 12,600 to 16,500 high, 138
         balloon texts with exact truth): read whole, 87 % of the texts were found and 4.5 % of their characters were
         wrong; cut into pieces at most 1.0, 1.4, 1.6, 2.0, 2.5, 3.0 or 4.0 times as high as wide, 93 to 96 % were
         found and 0.4 to 1.0 % were wrong, with no trend (one text is 0.7 %; the same lettering on its pages gives
         0.4 %). So the piece's exact shape matters little: 1.6 is a page's shape, and an image up to 2.2 times as
         high as wide is left whole.
cut      the detector is run on windows 1.4 times as high as the strip is wide, each half over the one before, and its
         text boxes and balloon boxes are gathered in the strip's own pixels. Then the rows to cut at are chosen from
         the top: a piece is at most PIECE (1.6) times as high as wide and, where the strip allows, at least 0.6; in
         that range the row taken is, in this order of preference,
           1. in a gutter: a row of one colour (whatever colour: white, black, a tint) that crosses no balloon and no
              lettering; the middle of the lowest such band;
           2. a row that crosses no balloon and no lettering (through art);
           3. a row that crosses a balloon but no lettering;
           4. any row (only a block of lettering higher than a whole piece comes to this).
         The pieces cover the strip from top to bottom with no gap and no overlap, and are written as PNG (no loss).
join     the pieces, each pasted at its top, into one image of the strip's size, saved in the format of the name given:
         JPEG (quality 92, 4:4:4), PNG, WebP (quality 92) or BMP, the four a comic's pages come in. `cut` takes any of
         them as well."""
import os
import numpy as np
from PIL import Image
from .geometry import area, drop_doubles, iou

STRIP, PIECE, LEAST, WINDOW = 2.2, 1.6, 0.6, 1.4

def is_strip(width, height):
    return height > STRIP * width

def find(image, detector):
    """(text boxes, balloon boxes) of a strip, in its own pixels, from overlapping windows."""
    w, h = image.size; wh = int(round(WINDOW * w)); step = max(1, wh // 2); tops = list(range(0, max(1, h - wh + 1), step))
    if not tops or tops[-1] + wh < h: tops.append(max(0, h - wh))
    text, balloons = [], []
    for top in tops:
        dets, bub = detector(image.crop((0, top, w, min(h, top + wh))))
        for d in dets: text.append({"cls": d["cls"], "score": d["score"], "box": [d["box"][0], d["box"][1] + top, d["box"][2], d["box"][3] + top]})
        balloons += [[b[0], b[1] + top, b[2], b[3] + top] for b in bub]
    text = drop_doubles(text); keep = []
    for b in sorted(balloons, key=area, reverse=True):
        if not any(iou(b, k) >= 0.5 for k in keep): keep.append(b)
    return text, keep

def rows_to_cut(rgb, text, balloons):
    """The rows a strip is cut at (the top of every piece but the first), and what each cut goes through."""
    h, w = rgb.shape[:2]
    spread = (rgb.max(axis=1).astype(np.int16) - rgb.min(axis=1).astype(np.int16)).max(axis=1)        # per row: how far its pixels differ
    flat = spread <= 24
    in_text = np.zeros(h, dtype=bool); in_balloon = np.zeros(h, dtype=bool)
    for d in text: in_text[max(0, int(d["box"][1]) - 4):min(h, int(np.ceil(d["box"][3])) + 4)] = True
    for b in balloons: in_balloon[max(0, int(b[1]) - 4):min(h, int(np.ceil(b[3])) + 4)] = True
    cost = np.where(in_text, 3, np.where(in_balloon, 2, np.where(flat, 0, 1)))
    cuts, why, top = [], [], 0
    names = {0: "gutter", 1: "art", 2: "balloon", 3: "lettering"}
    while h - top > PIECE * w:
        lo = top + int(LEAST * w); hi = top + int(PIECE * w)
        if h - hi < LEAST * w: hi = max(lo + 1, h - int(LEAST * w))                                   # leave a last piece worth reading
        seg = cost[lo:hi + 1]; best = int(seg.min()); ys = np.nonzero(seg == best)[0] + lo
        if best == 0:
            y1 = int(ys[-1]); y0 = y1
            while y0 - 1 >= lo and cost[y0 - 1] == 0: y0 -= 1
            y = (y0 + y1 + 1) // 2
        elif best == 1: y = int(ys[np.argmin(spread[ys] - 0.02 * (ys - lo))])                        # the calmest row, the lower the better
        else: y = int(ys[-1])
        cuts.append(y); why.append(names[best]); top = y
    return cuts, why

def cut(path, out_dir, detector):
    """Cuts a strip into pieces; an image that is no strip is left alone. Returns the reply of the `cut` request."""
    with Image.open(path) as im:
        img = im.convert("RGB")
    w, h = img.size
    if not is_strip(w, h): return {"strip": False, "width": w, "height": h, "pieces": []}
    text, balloons = find(img, detector)
    cuts, why = rows_to_cut(np.asarray(img), text, balloons)
    os.makedirs(out_dir, exist_ok=True); stem = os.path.splitext(os.path.basename(path))[0]; pieces = []
    tops = [0] + cuts; ends = cuts + [h]
    for n, (a, b) in enumerate(zip(tops, ends), 1):
        f = os.path.join(out_dir, f"{stem}_{n:03d}.png"); img.crop((0, a, w, b)).save(f)
        pieces.append({"file": f, "top": a, "height": b - a})
    return {"strip": True, "width": w, "height": h, "pieces": pieces, "cut_through": why}

def join(pieces, width, height, out):
    """Pastes the pieces ([{"file", "top"}], image files of any kind Pillow opens: PNG, JPEG, WebP, BMP) into one
    image of width x height and saves it; the format is `out`'s extension's (.jpg .jpeg .png .webp .bmp)."""
    canvas = Image.new("RGB", (int(width), int(height)), (255, 255, 255))
    for p in sorted(pieces, key=lambda p: p["top"]):
        with Image.open(p["file"]) as im:
            canvas.paste(im.convert("RGB"), (0, int(p["top"])))
    ext = os.path.splitext(out)[1].lower()
    if ext in (".jpg", ".jpeg"): canvas.save(out, format="JPEG", quality=92, subsampling=0)
    elif ext == ".webp": canvas.save(out, format="WEBP", quality=92)
    elif ext == ".png": canvas.save(out, format="PNG")
    elif ext == ".bmp": canvas.save(out, format="BMP")
    else: raise ValueError(f"join writes .jpg, .jpeg, .png, .webp or .bmp, not {ext or 'a name without an extension'}")
    return out
