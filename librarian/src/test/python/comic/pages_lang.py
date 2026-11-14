"""Pages drawn by the tests themselves for the languages other than Japanese: balloons lettered in lines (left to
right) or in columns, in any colours, and a web comic's strip. The sentences were written for these tests; no real
comic page is used here. Fonts are the caller's (conf.py)."""
from PIL import Image, ImageDraw, ImageFont

def _font(path, size, index=0):
    return ImageFont.truetype(path, size, index=index)

def balloon(img, at, lines, font_path, index=0, S=34, paper=(255, 255, 255), ink=(0, 0, 0), edge=(0, 0, 0), vertical=False, box=False, angle=0):
    """Draws one balloon centred at `at` and letters it. lines: the lines of text from the top (or, vertical, the
    columns from the right). box: a rectangle (a caption box) in place of an oval. angle: the lines rise to the right
    by so many degrees, as hand lettering does. Returns (text box, balloon box)."""
    d = ImageDraw.Draw(img); f = _font(font_path, S, index); cx, cy = at
    if vertical:
        pitch, colp = int(round(1.08 * S)), int(round(1.4 * S))
        tw = colp * (len(lines) - 1) + S; th = pitch * max(len(c) for c in lines)
    else:
        lh = int(round(1.25 * S)); ws = [d.textlength(l, font=f) for l in lines]
        tw = int(max(ws)); th = lh * len(lines)
    mx, my = int(0.22 * tw + 1.0 * S), int(0.3 * th + 1.0 * S)                 # an oval that holds the text's rectangle with room to spare
    bb = [cx - tw // 2 - mx, cy - th // 2 - my, cx + tw // 2 + mx, cy + th // 2 + my]
    if box: bb = [cx - tw // 2 - S, cy - th // 2 - S, cx + tw // 2 + S, cy + th // 2 + S]; d.rectangle(bb, fill=paper, outline=edge, width=4)
    else: d.ellipse(bb, fill=paper, outline=edge, width=4)
    if vertical:
        for ci, col in enumerate(lines):
            x = cx + tw // 2 - S - ci * colp
            for ri, ch in enumerate(col):
                g = f.getbbox(ch); d.text((x + (S - (g[2] - g[0])) / 2 - g[0], cy - th // 2 + ri * pitch + (S - (g[3] - g[1])) / 2 - g[1]), ch, font=f, fill=ink)
    elif angle:
        layer = Image.new("RGBA", (tw + 2 * S, th + 2 * S), (0, 0, 0, 0)); ld = ImageDraw.Draw(layer)
        for k, l in enumerate(lines): ld.text((S + (tw - ws[k]) / 2, S + k * lh), l, font=f, fill=tuple(ink) + (255,))
        layer = layer.rotate(angle, resample=Image.BICUBIC, expand=True)
        img.paste(layer, (cx - layer.width // 2, cy - layer.height // 2), layer)
    else:
        for k, l in enumerate(lines):
            d.text((cx - ws[k] / 2, cy - th // 2 + k * lh), l, font=f, fill=ink)
    return [cx - tw // 2 - 2, cy - th // 2 - 2, cx + tw // 2 + 2, cy + th // 2 + 2], bb

def panels(size=(1000, 1400), tone=(172, 172, 172), ground=(255, 255, 255)):
    """A page of three panels (one wide on top, two below) on a ground: the image."""
    img = Image.new("RGB", size, ground); d = ImageDraw.Draw(img); W, H = size
    for x0, y0, x1, y1 in ((40, 40, W - 40, int(0.46 * H)), (40, int(0.49 * H), W // 2 - 20, H - 40), (W // 2 + 20, int(0.49 * H), W - 40, H - 40)):
        d.rectangle([x0, y0, x1, y1], fill=tone, outline=(0, 0, 0), width=4)
    return img

def page(texts, font_path, index=0, S=34, vertical=False, colours=None, angle=0):
    """A three-panel page with up to four balloons, placed top left, top right, bottom left, bottom right (the order a
    left-to-right page is read in). texts: per balloon its lines (or columns). colours: per balloon (paper, ink) or None.
    angle: every balloon's lines rise to the right by so many degrees. Returns (image, [(text box, balloon box)])."""
    img = panels(); spots = [(270, 330), (720, 330), (260, 1010), (740, 1010)]; out = []
    for k, lines in enumerate(texts):
        paper, ink = (colours[k] if colours and colours[k] else ((255, 255, 255), (0, 0, 0)))
        edge = (0, 0, 0) if sum(paper) > 200 else (255, 255, 255)
        out.append(balloon(img, spots[k], lines, font_path, index, S, paper=paper, ink=ink, edge=edge, vertical=vertical, angle=angle))
    return img, out

GUTTERS = [(255, 255, 255), (0, 0, 0), (238, 226, 200), (255, 255, 255), (30, 40, 70), (255, 255, 255), (250, 250, 250)]

def strip(lines_of_panel, font_path, index=0, width=800, S=30):
    """A web comic's strip: one panel a text, each a toned rectangle 520..980 px high with one balloon, parted by
    gutters of several colours and heights. Returns (image, [balloon box], [(gutter top, gutter bottom)])."""
    heights = [620, 980, 540, 760, 900, 520, 840, 700]; gaps = [70, 160, 40, 300, 90, 120, 60, 200]
    parts = []; y = 30
    for k, lines in enumerate(lines_of_panel):
        h = heights[k % len(heights)]; parts.append((y, y + h, lines)); y += h + gaps[k % len(gaps)]
    H = y - gaps[(len(lines_of_panel) - 1) % len(gaps)] + 30
    img = Image.new("RGB", (width, H), (255, 255, 255)); d = ImageDraw.Draw(img); boxes, gutters = [], []
    for k, (a, b, lines) in enumerate(parts):
        if k + 1 < len(parts):
            d.rectangle([0, b, width, parts[k + 1][0]], fill=GUTTERS[k % len(GUTTERS)]); gutters.append((b, parts[k + 1][0]))
        tone = (150 + 12 * (k % 4), 160, 172 - 10 * (k % 3))
        d.rectangle([0 if k % 3 == 1 else 30, a, width if k % 3 == 1 else width - 30, b], fill=tone, outline=(0, 0, 0), width=4)
        _, bb = balloon(img, (width // 2 + (-90 if k % 2 else 90), a + (b - a) // 2), lines, font_path, index, S)
        boxes.append(bb)
    return img, boxes, gutters

def ground_page(font_path, index=0, S=30, width=800):
    """A piece of a web comic: a panel with a balloon; under it, on the open white ground, two lines of narration and a
    sound word; then a panel whose ground is hatched, with a line of text set straight on the hatching. Returns
    (image, {"narration": text box, "sound": text box, "on_art": text box, "panels": [panel boxes]})."""
    img = Image.new("RGB", (width, 1500), (255, 255, 255)); d = ImageDraw.Draw(img); f = _font(font_path, S, index)
    p1 = [40, 40, width - 40, 520]; p2 = [40, 900, width - 40, 1460]
    d.rectangle(p1, fill=(170, 176, 160), outline=(0, 0, 0), width=4)
    balloon(img, (width // 2, 280), ["Where is the doctor?", "He left an hour ago."], font_path, index, S)
    d.rectangle(p2, fill=(200, 200, 200), outline=(0, 0, 0), width=4)
    for y in range(p2[1] + 6, p2[3] - 4, 7): d.line([p2[0] + 4, y, p2[2] - 4, y], fill=(110, 110, 110), width=3)       # hatching: no flat ground
    def lines(texts, top):
        ws = [d.textlength(t, font=f) for t in texts]; lh = int(1.3 * S)
        for k, t in enumerate(texts): d.text(((width - ws[k]) / 2, top + k * lh), t, font=f, fill=(0, 0, 0))
        return [int((width - max(ws)) / 2) - 2, top - 2, int((width + max(ws)) / 2) + 2, top + lh * len(texts) + 2]
    narration = lines(["Nobody told me about the train", "that leaves before seven."], 640)
    fb = _font(font_path, int(1.4 * S), index); d.text((90, 780), "BZZZT!", font=fb, fill=(0, 0, 0)); sound = [88, 780, 90 + int(d.textlength("BZZZT!", font=fb)) + 2, 780 + int(1.6 * S)]
    on_art = lines(["It rained all night on the old roof."], 1150)
    return img, {"narration": narration, "sound": sound, "on_art": on_art, "panels": [p1, p2]}
