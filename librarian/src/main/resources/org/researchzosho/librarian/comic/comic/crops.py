"""crops: a block as a model that sees is shown it, for a second reading by the program's own model. The helper asks
no model; it only cuts the pictures.

A crop is the block's reading crop: the rectangle the helper's own reader was given (`"crop"` in the block as `read`
returns it; for a page dict without it, the text box with geometry.crop_box's margin), with the lettering of every
other block of the page covered (only_this_block). It is then made the size the
measurement showed the model: a crop whose long side is under 1024 px is enlarged, up to four times, to reach 1024
(Lanczos); one over 1536 px is made smaller to 1536; anything between is left as it is. Written as PNG, <id>.png.

Measured with crops made this way (the 27B, one crop a request, on the page detector's blocks of Pepper&Carrot
editions): characters wrong in balloon lettering, the line reader against the model: en 0.5 / 0.0, fr 0.5 / 0.0,
de 1.0 / 0.1, es 1.8 / 0.2, ru 5.4 / 1.1, Korean in a brush font 18.9 / 4.5, Chinese 1.3 / 0.4, drawn Chinese in
columns 12.1 / 4.4 %. For Japanese the earlier measurement went the other way (on OpenMantra's crops manga-ocr 3.2 %,
the model 4.4 % with its runaways capped: it rewrites vertical Japanese), so Japanese is not read a second time."""
import os
import numpy as np
from PIL import Image
from .geometry import crop_box

def only_this_block(img, page, blk, rect):
    """(the crop of a block with the lettering of every OTHER block covered, how many blocks were covered).
    A crop is a rectangle and a neighbour's lines fall into it: the next balloon's first words, the caption beside
    it. A model asked what is lettered "here" reads them too (read live: six of seven wrong second readings on
    hand-lettered pages held a neighbour's words). So what another block's lines cover in the crop (its `line_boxes`,
    its text box where the page dict has none, 2 px of margin) is painted with the colour around it: the commonest
    colour of a ring 8 px wide round the patch, which is the paper there, a caption's own colour in a caption box.
    Nothing of this block's own lines (its `line_boxes`, else its text box) is covered, whatever lies over them."""
    x1, y1, x2, y2 = [int(v) for v in rect]; c = np.asarray(img.crop((x1, y1, x2, y2))).copy(); h, w = c.shape[:2]
    def mark(m, boxes, g):
        for bx1, by1, bx2, by2 in boxes: m[max(0, int(by1) - g - y1):max(0, int(by2) + g - y1), max(0, int(bx1) - g - x1):max(0, int(bx2) + g - x1)] = True
        return m
    own = mark(np.zeros((h, w), bool), blk.get("line_boxes") or [blk["box"]], 2); n = 0
    for o in page["blocks"]:
        if o is blk or o.get("id") == blk.get("id"): continue
        patch = mark(np.zeros((h, w), bool), o.get("line_boxes") or [o["box"]], 2) & ~own
        if not patch.any(): continue
        ys, xs = np.nonzero(patch); ring = np.zeros((h, w), bool); ring[max(0, ys.min() - 8):ys.max() + 9, max(0, xs.min() - 8):xs.max() + 9] = True
        ring &= ~patch & ~own
        around = c[ring] if ring.any() else c.reshape(-1, 3)
        keys, counts = np.unique((around // 16).astype(np.int32) @ np.array([256, 16, 1]), return_counts=True)
        k = int(keys[counts.argmax()]); most = around[((around // 16).astype(np.int32) @ np.array([256, 16, 1])) == k]
        c[patch] = np.round(most.mean(axis=0)).astype(np.uint8); n += 1
    return Image.fromarray(c), n

def prepared(im):
    """The picture at the size the model is shown it."""
    long_side = max(im.size)
    if long_side < 1024:
        f = min(4.0, 1024.0 / long_side)
        return im.resize((max(1, round(im.width * f)), max(1, round(im.height * f))), Image.LANCZOS)
    if long_side > 1536:
        f = 1536.0 / long_side
        return im.resize((max(1, round(im.width * f)), max(1, round(im.height * f))))
    return im

def crops(image_path, page, out_dir, ids=None):
    """Writes the crops of a page's blocks into out_dir and returns [{"id", "file", "size": [w, h], "covered": n}],
    n the other blocks whose lettering was covered in the crop (only_this_block). page: one page of a read reply. ids: the blocks wanted, in that order; None = every speech block, in the page's order. An id the
    page does not have is an error."""
    with Image.open(image_path) as im:
        img = im.convert("RGB")
    by = {b["id"]: b for b in page["blocks"]}
    if ids is None: ids = [b["id"] for b in page["blocks"] if b.get("kind") == "speech"]
    missing = [i for i in ids if i not in by]
    if missing: raise ValueError("the page has no block " + ", ".join(str(i) for i in missing))
    os.makedirs(out_dir, exist_ok=True); out = []
    for i in ids:
        b = by[i]; rect = b.get("crop") or crop_box(b["box"], img.width, img.height)
        alone, covered = only_this_block(img, page, b, rect)
        c = prepared(alone); f = os.path.join(out_dir, f"{i}.png"); c.save(f)
        out.append({"id": i, "file": f, "size": [c.width, c.height], "covered": covered})
    return out
