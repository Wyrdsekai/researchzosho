"""A column of vertical Chinese or Japanese text: what stands in each cell, how long a piece is along its column, and
how a column is drawn. Every glyph is placed here, one cell at a time, with the font's plain horizontal glyphs: no
shaping library and no font feature is used, so a column comes out the same wherever Pillow runs.

A cell is one em high and one em wide. What stands in it:
  - a CJK character, a kana, a full-width sign: upright, as the font draws it in its em box;
  - 、 。 ，: the Unicode vertical presentation form (︑ ︒ ︐), which Japanese and simplified-Chinese fonts draw in the
    upper right of the cell; for traditional Chinese the mark is centred in the cell, as Taiwan and Hong Kong print it;
  - brackets, …, ‥, — : their vertical presentation forms (﹁ ﹂ ﹃ ﹄ ︵ ︶ ︙ ︰ ︱ and the rest of U+FE10..FE48);
  - the prolonged sound mark ー and the wave dash 〜: the glyph turned a quarter clockwise and mirrored, which is what
    the fonts' own vertical glyphs are; ― ─ ＝ and a bracket with no presentation form: turned a quarter clockwise;
    in Japanese the colon is turned as well; a run of dashes is drawn as one unbroken line;
  - ！ ？ ；: upright and centred (simplified-Chinese fonts would move ！ and ？ to the right of the column; a comic
    keeps them under the line they end); a run of them side by side, two or three to a cell (!? !! !!!);
  - small kana (っ ゃ ょ ァ ッ …): the glyph moved 0.11 em right and 0.12 em up, where the fonts' vertical glyphs stand;
  - curly quotation marks, which have no vertical shape of their own: simplified Chinese “ ” as ﹃ ﹄ and ‘ ’ as ﹁ ﹂
    (the national standard), traditional Chinese “ ” as ﹁ ﹂ and ‘ ’ as ﹃ ﹄, Japanese as 〝 〟 turned;
  - one or two digits or Latin letters: side by side in one cell, made narrower when they are wider than the cell;
  - a longer number (digits only), and a word of up to four capital letters: upright, one full-width character a cell;
  - any other run of Latin letters and digits (a word, 3.5, 100ml): turned a quarter clockwise, read from the top down;
  - a full-width space: an empty cell; any other space: half a cell.
The places were taken from the fonts' own vertical glyphs (Noto Sans CJK, measured with a shaping library on Linux and
compared cell by cell); see the notes of the measurement for the table."""
import re
import unicodedata
from PIL import Image, ImageDraw
from . import kinsoku

EM_TOP = 0.88                 # a CJK font's em box: the baseline lies 0.88 em under the top of the cell
SMALL_SHIFT = (0.11, -0.12)   # small kana in a column: right and up, in em
DOT_SHIFT = (0.45, -0.58)     # the full-width full stop ． from the lower left of its cell to the upper right
FORMS = {"，": "︐", "、": "︑", "。": "︒", "〖": "︗", "〗": "︘", "…": "︙", "‥": "︰", "—": "︱", "–": "︲",
         "（": "︵", "）": "︶", "｛": "︷", "｝": "︸", "〔": "︹", "〕": "︺", "【": "︻", "】": "︼", "《": "︽", "》": "︾",
         "〈": "︿", "〉": "﹀", "「": "﹁", "」": "﹂", "『": "﹃", "』": "﹄", "［": "﹇", "］": "﹈"}
CORNER = "、。，"             # drawn in the upper right (Japanese, simplified Chinese) or centred (traditional Chinese)
MIRRORED = "ー〜～ｰ"          # turned a quarter clockwise and mirrored
TURNED = "―─━＝｜＿〝〟〘〙｟｠«»‹›"   # turned a quarter clockwise
QUOTES = {"sc": {"“": "﹃", "”": "﹄", "‘": "﹁", "’": "﹂"}, "tc": {"“": "﹁", "”": "﹂", "‘": "﹃", "’": "﹄"},
          "jp": {"“": "〝", "”": "〟", "‘": "〝", "’": "〟"}}
WIDE = {c: chr(ord(c) + 0xFEE0) for c in "!?()[]{},.:;%&#$*+=<>/@~"}     # a narrow sign standing alone in a cell: its full-width form

def cells(text, region="jp"):
    """A stretch of a column as its cells: [(kind, glyphs, length in em or None)]. The kinds:
    'up' upright; 'mid' upright with its ink centred in the cell; 'shift' upright, moved by the two numbers that follow;
    'turn' turned a quarter clockwise; 'mirror' turned and mirrored; 'dash' a line through the whole cell;
    'pair' one or two narrow glyphs side by side; 'run' a run turned a quarter clockwise (its length is the font's to
    say: None here); 'gap' an empty stretch."""
    out = []; quotes = QUOTES.get(region, QUOTES["jp"]); parts = re.split(r"(\s+)", text.strip())
    for n in range(0, len(parts), 2):
        if n and parts[n]: out.append(("gap", "", 1.0 if "\u3000" in parts[n - 1] else 0.5))
        cl = kinsoku.clusters(parts[n]); i = 0
        while i < len(cl):
            c = cl[i]; i += 1
            if kinsoku.alnum(c[0]):
                t = unicodedata.normalize("NFKC", c)
                if len(t) <= 2: out.append(("pair", t, 1.0))
                elif t.isascii() and (t.isdigit() or (t.isalpha() and t.isupper() and len(t) <= 4)): out += [("up", chr(ord(x) + 0xFEE0), 1.0) for x in t]
                else: out.append(("run", t, None))
                continue
            if c in kinsoku.BANGS and i < len(cl) and cl[i] in kinsoku.BANGS:                 # a run of ！ ？: two or three side by side in a cell
                run = c
                while i < len(cl) and cl[i] in kinsoku.BANGS: run += cl[i]; i += 1
                run = unicodedata.normalize("NFKC", run); n = len(run); k = 0
                while k < n:
                    take = 3 if n - k == 3 or n - k > 4 else 2; out.append(("pair", run[k:k + take], 1.0)); k += take
                continue
            if c in ".．" and i < len(cl) and cl[i] in ".．":                      # dots typed one by one: three are an ellipsis
                n = 1
                while i < len(cl) and cl[i] in ".．": n += 1; i += 1
                out += [("up", FORMS["…"], 1.0)] * (n // 3) + ([("up", FORMS["‥"], 1.0)] if n % 3 == 2 else [("shift", "．", 1.0) + DOT_SHIFT] if n % 3 == 1 else [])
                continue
            ch = quotes.get(c, WIDE.get(c, c)); base = ch[0]
            if base in CORNER: out.append(("mid", ch, 1.0) if region == "tc" else ("up", FORMS[base], 1.0))
            elif base in kinsoku.DASHES: out.append(("dash", ch, 1.0))
            elif base in FORMS: out.append(("up", FORMS[base], 1.0))
            elif base in MIRRORED: out.append(("mirror", ch, 1.0))
            elif base in TURNED or (base == "：" and region == "jp"): out.append(("turn", ch, 1.0))
            elif base in kinsoku.SMALL: out.append(("shift", ch, 1.0) + SMALL_SHIFT)
            elif base == "．": out.append(("shift", ch, 1.0) + DOT_SHIFT)
            else: out.append(("up", ch, 1.0))
    return out

def length(text, font, size, region="jp"):
    """How long a stretch of text is along its column, in pixels."""
    return sum(size * c[2] if c[2] is not None else font.getlength(c[1]) + 0.2 * size for c in cells(text, region))

class Measure:
    """What setter.layout() asks of a font, answered for a column: the length of a piece from its top to its bottom."""
    def __init__(self, font, size, region):
        self.font, self.size, self.region = font, size, region
    def getlength(self, piece):
        if piece.isspace(): return self.size * (1.0 if "\u3000" in piece else 0.5)
        return length(piece, self.font, self.size, self.region)

def _glyph(font, size, text, pad):
    """The glyphs as the font draws them on a line, on a square of size + 2 pad whose middle is the em box (for one
    wide glyph) or the middle of the text's advance: white on black."""
    side = int(size + 2 * pad); im = Image.new("L", (side, side), 0)
    ImageDraw.Draw(im).text((pad + (size - font.getlength(text)) / 2.0, pad + EM_TOP * size), text, font=font, fill=255, anchor="ls")
    return im

def draw(canvas, font, size, text, xc, top, fill, region="jp"):
    """Draws one column on the canvas: the text's cells from `top` down, the column centred on xc. Returns the y under
    the last cell."""
    d = ImageDraw.Draw(canvas); y = float(top); x0 = xc - size / 2.0; pad = int(size // 2 + 2); cs = cells(text, region); i = 0
    while i < len(cs):
        kind, t, ln = cs[i][0], cs[i][1], cs[i][2]
        if kind == "gap": y += ln * size
        elif kind == "up": d.text((x0 + (size - font.getlength(t)) / 2.0, y + EM_TOP * size), t, font=font, fill=fill, anchor="ls"); y += size
        elif kind == "shift": d.text((x0 + (size - font.getlength(t)) / 2.0 + cs[i][3] * size, y + (EM_TOP + cs[i][4]) * size), t, font=font, fill=fill, anchor="ls"); y += size
        elif kind == "mid":
            g = _glyph(font, size, t, pad); bb = g.getbbox()
            if bb: canvas.paste(fill, (int(round(xc - (bb[0] + bb[2]) / 2.0)), int(round(y + size / 2.0 - (bb[1] + bb[3]) / 2.0))), g)
            y += size
        elif kind in ("turn", "mirror"):
            g = _glyph(font, size, t, pad).transpose(Image.ROTATE_270)
            if kind == "mirror": g = g.transpose(Image.FLIP_LEFT_RIGHT)
            canvas.paste(fill, (int(round(x0 - pad)), int(round(y - pad))), g); y += size
        elif kind == "dash":                                    # a run of dashes: one line down the middle of its cells
            n = 1
            while i + n < len(cs) and cs[i + n][0] == "dash": n += 1
            w = max(1.0, 0.06 * size); d.rectangle([xc - w / 2.0, y + 0.06 * size, xc + w / 2.0, y + (n - 0.06) * size], fill=fill)
            y += n * size; i += n; continue
        elif kind == "pair":
            w = font.getlength(t); im = Image.new("L", (int(w + 2 * pad), int(size + 2 * pad)), 0)
            ImageDraw.Draw(im).text((pad, pad + EM_TOP * size), t, font=font, fill=255, anchor="ls")
            if w > size:                                        # wider than the cell: made narrower, its height kept
                im = im.crop((pad, 0, int(pad + w + 1), im.height)).resize((int(size), im.height), Image.LANCZOS); canvas.paste(fill, (int(round(x0)), int(round(y - pad))), im)
            else: canvas.paste(fill, (int(round(xc - w / 2.0 - pad)), int(round(y - pad))), im)
            y += size
        elif kind == "run":
            w = font.getlength(t); im = Image.new("L", (int(w + 2 * pad), int(size + 2 * pad)), 0)
            ImageDraw.Draw(im).text((pad, pad + EM_TOP * size), t, font=font, fill=255, anchor="ls")
            g = im.transpose(Image.ROTATE_270)                  # the line's top goes to the right, its start to the top
            canvas.paste(fill, (int(round(x0 - pad)), int(round(y + 0.1 * size - pad))), g); y += w + 0.2 * size
        i += 1
    return y
