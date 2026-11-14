"""picture: a page as a model that sees is shown it. The page made smaller (its long side 1300 px by default) with each
block's box drawn in red and its number on a red tab above the box, so that a list of numbered texts can be matched to
places on the page. The page itself is not changed; the result is a JPEG."""
from PIL import Image, ImageDraw, ImageFont

RED = (220, 0, 0)

def _label_font(size):
    try: return ImageFont.load_default(size=size)
    except TypeError: return ImageFont.load_default()          # an older Pillow: its one small font

def numbered(image, boxes, long_side=1300):
    """The picture. boxes: [(label, [x1, y1, x2, y2])], in the page's own pixels."""
    im = image.convert("RGB"); f = min(1.0, float(long_side) / max(im.size))
    if f < 1.0: im = im.resize((max(1, round(im.width * f)), max(1, round(im.height * f))), Image.LANCZOS)
    else: im = im.copy()
    d = ImageDraw.Draw(im); tab = max(14, round(24 * max(im.size) / 1300.0)); font = _label_font(tab - 2)
    for label, box in boxes:
        x1, y1, x2, y2 = [v * f for v in box]
        d.rectangle([x1, y1, x2, y2], outline=RED, width=2)
        text = str(label); tw = d.textlength(text, font=font); top = max(0, y1 - tab)
        d.rectangle([x1, top, x1 + tw + 8, top + tab], fill=RED); d.text((x1 + 4, top), text, fill="white", font=font)
    return im
