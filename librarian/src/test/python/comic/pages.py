"""Pages and balloons drawn by the tests themselves: vertical Japanese text written for these tests, in a font the
caller names. No real comic page is used here."""
import random
from PIL import Image, ImageDraw, ImageFont

SENTENCES = ["薬を{N}ミリ用意しろ", "あと{N}人来ます", "費用は{N}円です", "残り{N}秒だ急げ", "患者は{N}歳の男性", "全部で{N}個届いた",
             "点数は{N}点でした", "室温は{N}度です", "毎月{N}万円払う", "今日は{N}件も来た", "体重は{N}キロだ", "部屋は{N}号室です",
             "まだ{N}枚残る", "合計{N}点になる", "あの{N}番を呼べ", "駅まで{N}分かかる"]

def _font(path, size, index=0):
    return ImageFont.truetype(path, size, index=index)

def _column_cells(d, img, cells, x, y0, S, pitch, font_path, index, ruby=None):
    """Draws cells down from (x, y0). A cell is ("ch", char) or ("num", digits set sideways in one cell).
    ruby: {cell index: kana} drawn small to the right, beside the cells it covers."""
    f = _font(font_path, S, index)
    for ri, (kind, v) in enumerate(cells):
        y = y0 + ri * pitch
        if kind == "ch":
            bb = f.getbbox(v); d.text((x + (S - (bb[2] - bb[0])) / 2 - bb[0], y + (S - (bb[3] - bb[1])) / 2 - bb[1]), v, font=f, fill=0)
        else:
            fd = _font(font_path, int(0.9 * S), index); bb = fd.getbbox(v)
            t = Image.new("L", (bb[2] - bb[0] + 4, bb[3] - bb[1] + 4), 255)
            ImageDraw.Draw(t).text((2 - bb[0], 2 - bb[1]), v, font=fd, fill=0)
            t = t.resize((min(t.width, int(0.98 * S)), int(0.8 * S)), Image.LANCZOS)
            img.paste(t, (x + (S - t.width) // 2, y + (S - t.height) // 2))
    for (first, n_cells), kana in (ruby or {}).items():
        r = max(10, int(0.45 * S)); fr = _font(font_path, r, index)
        top = y0 + first * pitch; span = n_cells * pitch - (pitch - S); step = span / len(kana)
        for k, ch in enumerate(kana):
            bb = fr.getbbox(ch); d.text((x + S + 3 - bb[0], top + k * step + (step - (bb[3] - bb[1])) / 2 - bb[1]), ch, font=fr, fill=0)

def balloon(sentence, number, sideways, S, rnd, font_path, index=0):
    """One balloon on grey, as in the sideways-digit measurement: (image, text box, balloon box)."""
    pitch = int(round(1.13 * S)); colp = int(round(1.45 * S)); cells = []
    pre, post = sentence.split("{N}")
    for ch in pre: cells.append(("ch", ch))
    if sideways: cells.append(("num", number))
    else:
        for dgt in number: cells.append(("ch", chr(ord(dgt) + 0xFEE0)))
    for ch in post: cells.append(("ch", ch))
    per = rnd.choice([5, 6, 7]); cols = [cells[i:i + per] for i in range(0, len(cells), per)]
    tw, th = colp * (len(cols) - 1) + S, pitch * max(len(c) for c in cols)
    mx, my = int(1.6 * S), int(1.5 * S); W, H = tw + 2 * mx, th + 2 * my
    img = Image.new("L", (W, H), 165); d = ImageDraw.Draw(img)
    d.ellipse([int(0.25 * S), int(0.25 * S), W - int(0.25 * S), H - int(0.25 * S)], fill=255, outline=0, width=3)
    for ci, col in enumerate(cols):
        _column_cells(d, img, col, mx + tw - S - ci * colp, my, S, pitch, font_path, index)
    return img, [mx - 2, my - 2, mx + tw + 2, my + th - (pitch - S) + 2], [int(0.25 * S), int(0.25 * S), W - int(0.25 * S), H - int(0.25 * S)]

def digit_set(n, font_path, index=0, seed=20261113):
    """The sideways-digit measurement's set: [(image, text box, balloon box, number, is control)]. One in five is a
    control with an upright single digit."""
    rnd = random.Random(seed); out = []
    for i in range(n):
        control = i % 5 == 4
        num = str(rnd.randint(1, 9)) if control else str(rnd.randint(10 ** (k := rnd.choice([1, 1, 2, 2, 3, 3, 3])), 10 ** (k + 1) - 1))
        s = rnd.choice(SENTENCES); S = rnd.choice([28, 32, 36])
        img, box, bal = balloon(s, num, not control, S, rnd, font_path, index)
        out.append((img, box, bal, num, control))
    return out

PAGE_TEXTS = {"plain": "明日の朝は七時に集合です", "ruby": "山田さんを呼んでください", "number": "部屋は204号室です", "sound": "ドドド"}

def page(font_path, index=0, S=34):
    """A page of three panels with three speech balloons (plain, one with furigana やまだ beside 山田, one with a
    sideways 204) and one drawn sound word whose strokes run over its small balloon's outline. Returns the image."""
    W, H = 1000, 1400; pitch = int(round(1.13 * S)); colp = int(round(1.45 * S))
    img = Image.new("L", (W, H), 255); d = ImageDraw.Draw(img)
    for x0, y0, x1, y1 in ((40, 40, 960, 640), (40, 680, 480, 1360), (520, 680, 960, 1360)):
        d.rectangle([x0, y0, x1, y1], fill=172, outline=0, width=4)
    def speech(cx, cy, cols, ruby=None):
        tw = colp * (len(cols) - 1) + S; th = pitch * max(len(c) for c in cols)
        bx0, by0, bx1, by1 = cx - tw // 2 - int(1.5 * S), cy - th // 2 - int(1.4 * S), cx + tw // 2 + int(1.7 * S), cy + th // 2 + int(1.4 * S)
        d.ellipse([bx0, by0, bx1, by1], fill=255, outline=0, width=4)
        for ci, col in enumerate(cols):
            _column_cells(d, img, col, cx + tw // 2 - S - ci * colp, cy - th // 2, S, pitch, font_path, index, ruby if ci == 0 else None)
    cells = lambda t: [("ch", c) for c in t]
    speech(740, 330, [cells("明日の朝は七時"), cells("に集合です")])
    speech(300, 330, [cells("山田さんを"), cells("呼んでください")], ruby={(0, 2): "やまだ"})
    speech(260, 1010, [cells("部屋は") + [("num", "204")] + cells("号室"), cells("です")])
    # a drawn sound word: heavy katakana larger than its small balloon, so its strokes join the outline
    d.ellipse([650, 930, 830, 1130], fill=255, outline=0, width=4)
    fb = _font(font_path, 78, index)
    for k, ch in enumerate("ドドド"):
        d.text((702, 905 + k * 80), ch, font=fb, fill=0, stroke_width=3, stroke_fill=0)
    return img
