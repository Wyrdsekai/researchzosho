"""Setting Chinese, Japanese and Korean: where a line may end (pure functions, no font), what stands in the cells of a
column (pure), and pages the test draws, set in columns and in lines: a column set and read back, the marks of a column
in their places, a Korean balloon, paper and ink, and the alphabetic setting unchanged by all of it.
The CJK font is COMIC_CJK_FONT (a font with the whole CJK set, as Noto Sans CJK has: the tests set Japanese, Chinese
and Korean with it). No real comic page and no text of one is used here."""
import hashlib, json, math, os, subprocess, sys, tempfile, unittest
import cv2
import numpy as np
from PIL import Image, ImageDraw, features
import PIL
import conf
from comic import kinsoku, vertical
from comic.balloon import mask_of
from comic.setter import DRAWN, Setter, Tight, Way, kept, layout, paint_of

def page_with(shapes, w=1000, h=1500, ground=(255, 255, 255)):
    """A page with balloons drawn on it and the page dict a read would give: shapes = [(id, "oval" | "box", cx, cy, rx, ry,
    paper or None, ink or None)]. A balloon with a paper is filled with it and its dict carries paper and ink."""
    img = Image.new("RGB", (w, h), ground); d = ImageDraw.Draw(img); blocks = []
    for bid, kind, cx, cy, rx, ry, paper, ink in shapes:
        fill = paper or (255, 255, 255); edge = (255, 255, 255) if sum(fill) < 200 else (0, 0, 0)
        if kind == "oval":
            d.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], fill=fill, outline=edge, width=4)
            outline = [[round(cx + (rx - 6) * math.cos(a * math.pi / 30)), round(cy + (ry - 6) * math.sin(a * math.pi / 30))] for a in range(60)]
        else:
            d.rectangle([cx - rx, cy - ry, cx + rx, cy + ry], fill=fill, outline=edge, width=4)
            outline = [[cx - rx + 6, cy - ry + 6], [cx + rx - 6, cy - ry + 6], [cx + rx - 6, cy + ry - 6], [cx - rx + 6, cy + ry - 6]]
        balloon = {"found": "balloon", "closed": True, "outline": outline}
        if paper: balloon["paper"] = list(paper); balloon["ink"] = list(ink)
        blocks.append({"id": bid, "box": [cx - 60, cy - 150, cx + 60, cy + 150], "score": 0.9, "kind": "speech", "balloon": balloon, "text": "こんにちは",
                       "readings": [], "numbers": [], "sound": None, "glyph_px": 36.0, "cell_px": 46.0, "look": []})
    return img, {"image": "drawn", "width": w, "height": h, "dialogue_glyph_px": 36.0, "order": [b["id"] for b in blocks], "blocks": blocks}

def one_oval(rx=260, ry=330): return page_with([("b01", "oval", 500, 600, rx, ry, None, None)])

def ink_columns(img, block, dark=True):
    """The columns of lettering inside a block's balloon, from the right: [(x1, x2, y1, y2)] of each run of the ink's
    column profile."""
    m, (ox, oy) = mask_of(block["balloon"]["outline"], (img.height, img.width)); g = np.asarray(img.convert("L"))[oy:oy + m.shape[0], ox:ox + m.shape[1]]
    ink = ((g < 128) if dark else (g > 128)) & m; prof = ink.sum(axis=0) > 0; out, s = [], None
    for x, v in enumerate(list(prof) + [False]):
        if v and s is None: s = x
        if not v and s is not None:
            ys = np.nonzero(ink[:, s:x].sum(axis=1))[0]; out.append((ox + s, ox + x, oy + int(ys[0]), oy + int(ys[-1]) + 1)); s = None
    return out[::-1]

class Measure:
    """A font for the search that measures every character 10 wide."""
    def getlength(self, s): return 10.0 * len(s)

class Kinsoku(unittest.TestCase):
    SAMPLES = ["「そうか、わかった。」", "えっ！？ちょっと、まってよーっ！！", "……なに――それ（まさか）", "ジャーナリストは々々しい〜", "DNAは100％、3.5ミリだ", "“什么？！”他说——真的吗……",
               "《书名》（注）：好，再见！", "「你好」、世界。", "１２時に「集合」です。", "あ．．．．．あの．．．"]

    def test_no_unit_begins_or_ends_where_a_line_may_not(self):
        for text in self.SAMPLES:
            us = kinsoku.units(text); self.assertEqual("".join(us), text)
            for i, u in enumerate(us):
                if i: self.assertNotIn(u[0], kinsoku.NO_START, (text, us))
                if i < len(us) - 1: self.assertNotIn(u[-1], kinsoku.NO_END, (text, us))
                if i: self.assertFalse(us[i - 1][-1] in kinsoku.DOTS and u[0] in kinsoku.DOTS, (text, us)); self.assertFalse(us[i - 1][-1] in kinsoku.DASHES and u[0] in kinsoku.DASHES, (text, us))

    def test_units_as_the_rules_give_them(self):
        self.assertEqual(kinsoku.units("「そうか、わかった。」"), ["「そ", "う", "か、", "わ", "かっ", "た。」"])                   # brackets and marks stay with their letters
        self.assertEqual(kinsoku.units("えっ！？まってよーっ"), ["えっ！？", "まっ", "て", "よーっ"])                          # small kana, the long mark and ！？ stay with what is before them
        self.assertEqual(kinsoku.units("DNAは100％、3.5ミリ"), ["DNA", "は", "100％、", "3.5", "ミ", "リ"])              # letters and digits are never split
        self.assertEqual(kinsoku.units("……なに――それ"), ["……", "な", "に", "――", "そ", "れ"])                            # …… and —— are never split
        self.assertEqual(kinsoku.units("“什么？！”他说"), ["“什", "么？！”", "他", "说"])                                 # Chinese marks by the same rules
        self.assertEqual(kinsoku.units("人々のゝ"), ["人々", "のゝ"])                                                     # iteration marks

    def test_atoms_say_what_may_happen_after_each_piece(self):
        for text, lang in (("今日は天気がいいので、公園へ行きましょう。", "ja"), ("今天天气很好，我们一起去公园散步吧。", "zh-Hans"), ("今天天氣很好，我們一起去公園散步吧。", "zh-Hant")):
            at = kinsoku.atoms(text, lang); self.assertEqual("".join(p for p, _ in at), text); self.assertEqual(at[-1][1], "word")
            self.assertTrue(all(k in ("nat", "soft", "in", "mid", "word") for _, k in at))
            comma = next(k for p, k in at if p[-1] in "、，"); self.assertEqual(comma, "nat")                             # a phrase ends at a comma
        self.assertEqual(dict(kinsoku.atoms("３０分かかる", "ja"))["３０"], "in"); self.assertEqual(dict(kinsoku.atoms("30分钟以前", "zh"))["30"], "in")     # a number and its counter
        self.assertEqual(dict(kinsoku.atoms("我们的书", "zh"))["们"], "in")                                              # 的 leans on the word before it
        self.assertEqual(kinsoku.atoms("「はい」と言った", "ja")[0][0], "「は"); self.assertNotEqual(dict(kinsoku.atoms("「はい」と言った", "ja"))["い」"], "nat")     # と may follow 」
        self.assertEqual(kinsoku.atoms("えっ！？ なに", "ja")[0], ("えっ！？", "word"))                                     # a space ends a piece
        self.assertEqual(kinsoku.atoms("えっ！？\u3000なに", "ja")[0], ("えっ！？", "wide"))                                # and a full-width space is remembered as one

    def test_marks_and_quoted_words_stay_with_their_words(self):
        for text, lang in (("这叫“自由诊疗”吧", "zh"), ("これが「自由診療」です", "ja")):
            at, n = kinsoku.atoms(text, lang), 0; a, b = text.index(text[text.index("自") - 1]), text.index("療" if lang == "ja" else "疗") + 1
            for piece, kind in at[:-1]:
                n += len(piece)
                if a + 1 < n < b + 1: self.assertIn(kind, ("in", "mid"), (text, at))                                                    # no free end inside short quotation marks
        self.assertEqual(dict(kinsoku.atoms("你注意到了吗……", "zh"))["吗"], "in")                                                       # …… trails the word before it
        self.assertIsNone(layout([("吗", "soft"), ("……？", "word")], [15, 35], Measure(), False, strict=2, lone=0.3))                   # strict 2: no line of marks only

    def test_kept_strings_are_one_piece(self):
        at = [("Dr.", "word"), ("Ei", "hyph"), ("roku", "word"), ("Uni", "hyph"), ("ver", "hyph"), ("sity", "word"), ("called.", "word")]
        self.assertEqual(kept(at, ["Eiroku University"]), [("Dr.", "word"), ("Eiroku University", "word"), ("called.", "word")])            # no line end at its space, no hyphen in it
        self.assertEqual(kept(at, ["University"]), at[:3] + [("University", "word"), ("called.", "word")]); self.assertIs(kept(at, ["Tokyo"]), at)
        zh = [("齐", "soft"), ("藤", "nat"), ("英", "soft"), ("二", "mid"), ("郎", "nat"), ("25", "in"), ("岁", "word")]
        self.assertEqual(kept(zh, ["齐藤英二郎"]), [("齐藤英二郎", "nat"), ("25", "in"), ("岁", "word")])
        self.assertEqual(layout(kept(zh, ["齐藤英二郎"]), [55, 35], Measure(), False)[1], ["齐藤英二郎", "25岁"])

    def test_korean_breaks_at_spaces_and_in_a_long_word_only(self):
        at = kinsoku.atoms("오늘은 날씨가 좋으니까 갑시다.", "ko")
        self.assertEqual(at, [("오늘은", "word"), ("날씨가", "word"), ("좋으", "syl"), ("니까", "word"), ("갑시다.", "word")])
        self.assertEqual(kinsoku.atoms("……그러니까", "ko"), [("……그러", "syl"), ("니까", "word")])                        # its marks are not counted as syllables
        self.assertEqual(kinsoku.atoms("에이다이에서", "ko"), [("에이", "syl"), ("다", "syl"), ("이", "syl"), ("에서", "word")])        # two syllables at least on each side
        self.assertEqual(kinsoku.atoms("갑시다", "ko"), [("갑시다", "word")])                                                   # a short word is never broken

    def test_regions(self):
        self.assertEqual([kinsoku.region(c) for c in ("ja", "zh", "zh-Hans", "zh-CN", "zh-Hant", "zh-TW", "zh_HK", "ko", "en")], ["jp", "sc", "sc", "sc", "tc", "tc", "tc", "kr", None])
        self.assertTrue(kinsoku.is_cjk("ko-KR")); self.assertFalse(kinsoku.is_cjk("ru"))

    @unittest.skipUnless(kinsoku.phrases_known("ja"), "neither the dictionary (fugashi) nor budoux is installed")
    def test_japanese_phrases(self):
        kinds = dict(kinsoku.atoms("医師国家試験に合格しなければいけない", "ja")); self.assertEqual(kinds["に"], "nat")
        self.assertNotEqual(kinds["格"], "nat")                                                                         # not before する's し

    def test_the_search_ends_lines_where_the_atoms_say(self):
        at = [("今", "in"), ("日", "in"), ("は", "nat"), ("晴", "in"), ("れ", "in"), ("だ", "word")]
        self.assertEqual(layout(at, [45, 45], Measure(), False)[1], ["今日は", "晴れだ"])
        got = layout([("こ", "in"), ("れ", "in"), ("は", "in"), ("本", "in"), ("だ", "word")], [31, 31], Measure(), False); self.assertEqual(got[1], ["これは", "本だ"]); self.assertEqual(got[3], 1)
        self.assertIsNone(layout([("こ", "in"), ("れ", "in"), ("は", "in"), ("本", "in"), ("だ", "word")], [31, 31], Measure(), False, strict=2))          # strict 2: no line ends inside a phrase
        self.assertIsNone(layout([("あ", "nat"), ("いうえお", "word")], [45, 45], Measure(), False, strict=2, lone=0.3))                                  # strict 2: no line of one character
        word = [("若", "in"), ("す", "mid"), ("ぎ", "mid"), ("る", "in"), ("で", "mid"), ("す", "word")]                                                       # strict 1: a line may end before a particle, never in the middle of a word
        self.assertEqual(layout(word, [35, 35], Measure(), False)[4], 1); self.assertIsNone(layout(word, [35, 35], Measure(), False, strict=1)); self.assertEqual(layout(word, [45, 25], Measure(), False, strict=1)[1], ["若すぎる", "です"])
        ko = [("좋으", "syl"), ("니까", "word")]
        self.assertIsNone(layout(ko, [25, 25], Measure(), False)); got = layout(ko, [25, 25], Measure(), True); self.assertEqual(got[1], ["좋으", "니까"]); self.assertEqual(got[2], 1)   # a broken word, no hyphen
        latin = [("im", "hyph"), ("mediately", "word")]                                                                           # the alphabetic kinds as they were
        self.assertIsNone(layout(latin, [35, 95], Measure(), False)); got = layout(latin, [35, 95], Measure(), True); self.assertEqual(got[1], ["im-", "mediately"]); self.assertEqual(got[2], 1)
        self.assertEqual(layout([("Good", "word"), ("morning.", "word")], [45, 85], Measure(), False)[1], ["Good", "morning."])
        self.assertEqual(layout([("えっ！", "wide"), ("なに", "word")], [75], Measure(), False)[1], ["えっ！\u3000なに"])

class ChineseWords(unittest.TestCase):
    """The word list is the manifest's; here a list of a few words written by the test stands in for it."""
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        with open(os.path.join(self.tmp.name, "chinese.txt"), "w", encoding="utf-8") as f: f.write("交通 300 n\n事故 200 n\n交通事故 150 n\n患者 120 n\n的 9000 uj\n接收 90 v\n我们 800 r\n只 700 d\n")
        kinsoku.words_in(self.tmp.name)
    def tearDown(self):
        kinsoku.words_in(None); self.tmp.cleanup()

    def test_words_end_where_the_list_says(self):
        w = kinsoku.chinese_words(); self.assertEqual(sorted(w.ends("我们只接收交通事故的患者")), [2, 3, 5, 9, 10, 12]); self.assertEqual(sorted(w.ends("甲乙交通")), [1, 2, 4])     # an unknown stretch falls into characters
    def test_no_line_ends_free_in_the_middle_of_a_word(self):
        kinds = kinsoku.atoms("我们只接收交通事故的患者", "zh-Hans"); self.assertEqual("".join(p for p, _ in kinds), "我们只接收交通事故的患者")
        by_offset, n = {}, 0
        for piece, kind in kinds[:-1]: n += len(piece); by_offset[n] = kind
        for inside in (4, 6, 7, 8, 11): self.assertEqual(by_offset[inside], "mid", (inside, kinds))                                    # 接|收, 交|通|事|故, 患|者
        self.assertEqual(by_offset[1], "in")                                                                                           # 我|们: 们 leans on 我, which is as dear
        for end in (2, 3, 5, 10): self.assertIn(by_offset[end], ("nat", "soft"), (end, kinds))                                         # a word's end is free or cheap
        self.assertEqual(by_offset[9], "in")                                                                                           # 的 leans on the word before it
        self.assertTrue(kinsoku.phrases_known("zh")); kinsoku.words_in(None); self.assertNotIn("mid", [k for _, k in kinsoku.atoms("交通事故", "zh")])

class Cells(unittest.TestCase):
    def kinds(self, text, region="jp"): return [(c[0], c[1]) for c in vertical.cells(text, region)]

    def test_marks_take_their_vertical_forms(self):
        self.assertEqual(self.kinds("「そうか、わかった。」"), [("up", "﹁"), ("up", "そ"), ("up", "う"), ("up", "か"), ("up", "︑"), ("up", "わ"), ("up", "か"), ("shift", "っ"), ("up", "た"), ("up", "︒"), ("up", "﹂")])
        self.assertEqual(self.kinds("（注）『本』…―"), [("up", "︵"), ("up", "注"), ("up", "︶"), ("up", "﹃"), ("up", "本"), ("up", "﹄"), ("up", "︙"), ("dash", "―")])
        self.assertEqual(self.kinds("ジャーナル〜"), [("up", "ジ"), ("shift", "ャ"), ("mirror", "ー"), ("up", "ナ"), ("up", "ル"), ("mirror", "〜")])
        self.assertEqual(self.kinds("a：b"), [("pair", "a"), ("turn", "："), ("pair", "b")]); self.assertEqual(self.kinds("a：b", "sc")[1], ("up", "："))    # the colon is turned in Japanese only

    def test_bangs_digits_and_letters(self):
        self.assertEqual(self.kinds("だ！！！"), [("up", "だ"), ("pair", "!!!")]); self.assertEqual(self.kinds("だ！！！！"), [("up", "だ"), ("pair", "!!"), ("pair", "!!")]); self.assertEqual(self.kinds("だ？！！！！"), [("up", "だ"), ("pair", "?!!"), ("pair", "!!")])     # a run: two or three to a cell
        self.assertEqual(self.kinds("えっ！？"), [("up", "え"), ("shift", "っ"), ("pair", "!?")]); self.assertEqual(self.kinds("だ！"), [("up", "だ"), ("up", "！")]); self.assertEqual(self.kinds("だ!"), [("up", "だ"), ("up", "！")])
        self.assertEqual(self.kinds("１２時"), [("pair", "12"), ("up", "時")]); self.assertEqual(self.kinds("1000円"), [("up", "１"), ("up", "０"), ("up", "０"), ("up", "０"), ("up", "円")])
        self.assertEqual(self.kinds("DNAとBlack"), [("up", "Ｄ"), ("up", "Ｎ"), ("up", "Ａ"), ("up", "と"), ("run", "Black")])
        self.assertEqual(self.kinds("あ．．．"), [("up", "あ"), ("up", "︙")]); self.assertEqual([c[0] for c in vertical.cells("はい いいえ")], ["up", "up", "gap", "up", "up", "up"])

    def test_chinese_by_region(self):
        self.assertEqual(self.kinds("好，对。", "sc"), [("up", "好"), ("up", "︐"), ("up", "对"), ("up", "︒")]); self.assertEqual(self.kinds("好，對。", "tc"), [("up", "好"), ("mid", "，"), ("up", "對"), ("mid", "。")])
        self.assertEqual(self.kinds("“好”", "sc"), [("up", "﹃"), ("up", "好"), ("up", "﹄")]); self.assertEqual(self.kinds("“好”", "tc"), [("up", "﹁"), ("up", "好"), ("up", "﹂")])
        self.assertEqual(vertical.cells("你 好", "sc")[1], ("gap", "", 0.5)); self.assertEqual(vertical.cells("はい\u3000いいえ", "jp")[2], ("gap", "", 1.0))     # a space as wide as it was written

class PaintOfAClippedBalloon(unittest.TestCase):
    """Found on real pages (2026-11-14). Where a balloon's inside is the detector's box and not its own shape, the paint
    went by the block's text box: art that reached into that box was painted over in squares (five of 6 to 13 px beside
    one burst), and a first letter the box cut through stayed. And an inside clipped to a balloon box that did not hold
    the block's first or last line left that line standing, with its translation set under it: said twice."""
    def test_only_the_lines_the_reader_read_are_painted(self):
        from comic.setter import own_lettering
        ink = np.zeros((120, 300), np.uint8); safe = np.zeros((120, 300), bool); safe[2:-2, 2:-2] = True
        ink[30:50, 40:200] = 1                      # the lettering: one line the reader read
        ink[70:82, 210:222] = 1                     # art that reaches into the text box, clear of the line
        ink[34:46, 22:34] = 1                       # the line's first letter, which the detector's text box cut off
        ink[42:46, 214:218] = 1                     # the last dot of the line's ellipsis, which the line's own box stops short of
        blk = {"balloon": {"found": "balloon, open: clipped to the box"}, "box": [40, 26, 230, 80], "glyph_px": 20.0}
        old = own_lettering(ink.copy(), safe, blk, 0, 0)
        self.assertTrue(old[70:82, 210:222].all()); self.assertFalse(old[34:46, 22:28].any())                                   # by the text box alone: the art goes, the first letter stays
        blk["line_boxes"] = [[20, 28, 202, 52]]
        new = own_lettering(ink.copy(), safe, blk, 0, 0)
        self.assertTrue(new[30:50, 40:200].all()); self.assertTrue(new[34:46, 22:34].all(), "the line's first letter is the block's own lettering")
        self.assertFalse(new[70:82, 210:222].any(), "what stands clear of every line the reader read is not the block's lettering")
        self.assertTrue(new[42:46, 214:218].all(), "a mark at a line's height, inside the text box, is the line's own: the dots of an ellipsis the line reader's box stops short of")
        blk["balloon"]["found"] = "balloon"                                                                                     # a balloon with its own shape: everything dark in it, as before
        self.assertTrue((own_lettering(ink.copy(), safe, blk, 0, 0) == ink).all())

    def test_lettering_that_touches_the_outline_is_painted_where_the_reader_read_a_line(self):
        """Hand lettering runs up to the balloon's outline and into it (a comic book of 1940, read live): a letter joined
        to the outline was "the outline coming in" and stayed, so half the balloons of a page kept pieces of their old
        lines under the new ones. A line the reader read is lettering: its dark pixels inside the balloon are painted
        though they reach the edge; what reaches the edge clear of every line is still left."""
        from comic.setter import own_lettering
        ink = np.zeros((120, 300), np.uint8); safe = np.zeros((120, 300), bool); safe[2:-2, 2:-2] = True
        ink[2:40, 60:64] = 1; ink[30:44, 60:200] = 1        # a line of lettering whose first letter runs up into the outline at the top
        ink[2:118, 280:284] = 1                               # a stroke of the art, or the outline itself, crossing the inside clear of the line
        ink[70:84, 60:200] = 1                                # a second line, standing free
        ink = ink * safe.astype(np.uint8)
        blk = {"balloon": {"found": "balloon"}, "box": [56, 26, 204, 88], "glyph_px": 14.0}
        old = own_lettering(ink.copy(), safe, blk, 0, 0)
        self.assertFalse(old[30:44, 60:200].any(), "without the lines, the whole first line is left with the letter that reaches the edge"); self.assertTrue(old[70:84, 60:200].all())
        blk["line_boxes"] = [[58, 28, 202, 46], [58, 68, 202, 86]]
        new = own_lettering(ink.copy(), safe, blk, 0, 0)
        self.assertTrue(new[30:44, 60:200].all(), "the line the reader read is painted"); self.assertTrue(new[70:84, 60:200].all())
        self.assertFalse(new[60:118, 280:284].any(), "what reaches the edge clear of every line is left"); self.assertFalse(new[2:20, 60:64].any(), "and so is the part of the stroke above the line: the outline it joins")

    def test_an_inside_clipped_to_the_balloon_box_still_holds_the_blocks_text_box(self):
        from comic.balloon import interior
        gray = np.full((300, 400), 110, np.uint8); gray[50:170, :] = 255                # a band of paper that runs off both sides: no closed balloon is found
        gray[64:80, 150:250:4] = 0; gray[100:116, 150:250:4] = 0; gray[130:146, 150:250:4] = 0   # three lines of lettering
        text = [146, 60, 254, 150]; bubble = [140, 96, 260, 160]                         # the detector's balloon box starts under the first line
        found = interior(gray, text, [bubble])
        self.assertIn("clipped", found["how"])
        ox, oy = found["origin"]; f = found["filled"]
        self.assertTrue(f[72 - oy, 200 - ox], "the first line, which the block's text box holds, is inside")
        self.assertTrue(f[138 - oy, 200 - ox]); self.assertFalse(f[72 - oy, 275 - ox], "and nothing beside the two boxes")

class ALineThatRunsIntoTheOutline(unittest.TestCase):
    """Read live (2026-11-14): hand lettering of the 1940s runs up to its balloon's outline and into it. A letter joined
    to the outline is no part of the paper's inside (it is not enclosed by the paper), so it was never painted: on 14
    such pages the first line of many balloons stood above its translation. What lies in a line the reader read is
    lettering: there the dark is painted across the hull of the inside too, to 2 px from the hull's edge; the outline
    stays wherever no line is."""
    def balloon(self):
        from comic import balloon as bal
        img = Image.new("RGB", (700, 420), (120, 150, 190)); d = ImageDraw.Draw(img)
        d.rounded_rectangle([60, 60, 640, 360], radius=60, fill=(246, 240, 225), outline=(15, 15, 15), width=6)
        for x in range(150, 540, 34): d.rectangle([x, 60, x + 21, 104], fill=(15, 15, 15))                 # the first line: every letter joined to the outline at the top
        d.rectangle([150, 101, 545, 104], fill=(15, 15, 15))                                                 # and to its neighbours at the foot: the paper between the letters is closed off from the balloon's
        for y in (140, 200):
            for x in range(130, 560, 34): d.rectangle([x, y, x + 21, y + 34], fill=(15, 15, 15))            # two lines standing free
        d.line([640, 200, 500, 300], fill=(15, 15, 15), width=5)                                             # a stroke of the art coming in from the outline, clear of every line
        rgb = np.asarray(img); gray = np.asarray(img.convert("L")); tb = [126, 66, 566, 238]
        found = bal.interior(gray, tb, [[58, 58, 642, 362]], rgb, hull=True)                                # the inside as the reader finds it, with the detector's balloon box
        assert found["how"] == "balloon" and found["closed"], found["how"]
        blk = {"id": "b01", "kind": "speech", "box": tb, "glyph_px": 34.0, "balloon": {"found": found["how"], "closed": True, "outline": bal.outline(found), "paper": found["paper"], "ink": found["ink"]},
               "line_boxes": [[148, 62, 544, 106], [128, 138, 562, 176], [128, 198, 562, 236]]}
        return img, rgb, gray, blk

    def test_a_letter_joined_to_the_outline_is_painted_and_the_outline_stays(self):
        from comic.setter import paint_of
        img, rgb, gray, blk = self.balloon()
        filled, (ox, oy) = mask_of(blk["balloon"]["outline"], gray.shape)
        paint, paper, pen, stays, _ = paint_of(rgb, gray, blk, filled, ox, oy)
        on = np.zeros(gray.shape, bool); on[oy:oy + paint.shape[0], ox:ox + paint.shape[1]] = paint
        first = np.zeros(gray.shape, bool)
        for x in range(150, 540, 34): first[70:105, x:x + 22] = True                                        # the letters of the first line, under the outline's own 6 px and a little
        self.assertGreater(on[first].mean(), 0.9, "the first line's letters are painted though they join the outline: %.2f" % on[first].mean())
        free = np.zeros(gray.shape, bool)
        for y in (140, 200):
            for x in range(130, 560, 34): free[y:y + 35, x:x + 22] = True
        self.assertGreater(on[free].mean(), 0.99)
        self.assertFalse(on[58:64, 80:140].any() or on[58:64, 560:620].any(), "the outline beside the line stands")
        self.assertFalse(on[58:63, 150:540].any(), "and so does the outline's own edge over the letters")
        stroke = np.zeros(gray.shape, bool); stroke[204:250, 566:638] = gray[204:250, 566:638] < 100
        self.assertGreater(int(stroke.sum()), 150); self.assertLess(on[stroke].mean(), 0.05, "a stroke that comes in from the outline clear of every line is left")
        self.assertLess(stays, DRAWN, "and the block is lettered, not left as art")

    def test_a_line_that_stands_outside_the_inside_is_not_painted(self):
        """A block that holds a second balloon's line (seen live: an order and, beside it, "we understand!" in a balloon of
        its own): that line's rectangle lies in the hull of the first balloon's inside where the two stand close, and a
        letter of it was painted away. Under a third of such a line lies in the inside itself: it is not this balloon's."""
        from comic.setter import paint_of, lines_in_the_hull
        img, rgb, gray, blk = self.balloon()
        filled, (ox, oy) = mask_of(blk["balloon"]["outline"], gray.shape)
        blk["line_boxes"].append([622, 250, 712, 290])                                                      # a line nearly all outside the balloon, its left end in the hull
        zone = lines_in_the_hull(filled, blk, ox, oy); on = np.zeros(gray.shape, bool); on[oy:oy + zone.shape[0], ox:ox + zone.shape[1]] = zone
        self.assertFalse(on[250:290, 622:712].any()); self.assertTrue(on[70:100, 160:500].any(), "the first line's own rectangle still counts")

    def test_without_the_lines_nothing_outside_the_inside_is_painted(self):
        from comic.setter import paint_of
        img, rgb, gray, blk = self.balloon(); del blk["line_boxes"]                                         # Japanese, or an older page dict
        filled, (ox, oy) = mask_of(blk["balloon"]["outline"], gray.shape)
        paint = paint_of(rgb, gray, blk, filled, ox, oy)[0]
        self.assertFalse((paint & ~filled).any())

@conf.need("COMIC_FONT")
class PaperAndInkTooClose(unittest.TestCase):
    """The guard for what no rule foresees: a block whose paper and ink the reading gave as nearly one colour would be
    painted and lettered in two colours no eye tells apart. Under a contrast of 2.0 (the measure of the web's
    accessibility rules, 1 to 21) it is left as it is and says why. Chosen on 1,097 speech blocks of eight real
    comics: the lowest there is 3.2 (dark brown on an orange caption box, which reads), none is under 3."""
    def test_a_block_whose_ink_would_not_show_on_its_paper_is_left_and_says_why(self):
        from comic.setter import contrast, CONTRAST
        self.assertEqual(CONTRAST, 2.0); self.assertAlmostEqual(contrast((255, 255, 255), (0, 0, 0)), 21.0, places=1); self.assertGreater(contrast((199, 105, 57), (83, 41, 20)), 3.0)
        img, page = page_with([("b01", "oval", 300, 400, 220, 160, None, None), ("b02", "oval", 700, 400, 220, 160, None, None)])
        page["blocks"][0]["balloon"]["paper"] = [200, 200, 200]; page["blocks"][0]["balloon"]["ink"] = [170, 170, 170]       # contrast 1.4
        out, rep = Setter(conf.FONT).set_page(img, page, {"b01": {"text": "This would not be read", "lang": "en"}, "b02": {"text": "And this is read", "lang": "en"}})
        self.assertFalse(rep["blocks"]["b01"]["set"]); self.assertEqual(rep["blocks"]["b01"]["why_not"], "the balloon's paper and its lettering are too close in colour for lettering to be read on it: left as it is")
        self.assertTrue(rep["blocks"]["b02"]["set"])
        a = np.asarray(img.convert("L")); b = np.asarray(out.convert("L")); self.assertTrue((a[:, :500] == b[:, :500]).all(), "nothing of the first balloon is painted")

class ADateIsNotBrokenAtItsHyphens(unittest.TestCase):
    """Seen on a real page (2026-11-14): "(2008-" at a line's end and "11-25)" on the next. A word's own hyphen is a
    place a line may end ("RISA-" / "CHAN"); a hyphen between two digits is not: a date, a range of numbers, a score."""
    def test_a_hyphen_between_two_digits_is_no_place_to_end_a_line(self):
        from comic.setter import atoms_of
        self.assertEqual(atoms_of("(2008-11-25) FIRST", None), [("(2008-11-25)", "word"), ("FIRST", "word")])
        self.assertEqual(atoms_of("PAGES 12–14", None), [("PAGES", "word"), ("12–14", "word")])
        self.assertEqual(atoms_of("RISA-CHAN", None), [("RISA-", "nat"), ("CHAN", "word")])
        self.assertEqual(atoms_of("COVID-19 X-2", None), [("COVID-", "nat"), ("19", "word"), ("X-", "nat"), ("2", "word")])

@conf.need("COMIC_FONT")
class BlocksThatShareABalloon(unittest.TestCase):
    """Found on real pages (2026-11-14): a balloon lettered in two or three groups of columns is two or three text
    blocks to the page detector, each with the whole balloon for its inside. Each translation was set in the middle of
    the balloon, one over the other: ten such places in eleven pages of a comic in Chinese set in English."""
    def test_each_block_is_set_in_its_own_part_of_the_balloon(self):
        img, page = page_with([("b01", "oval", 500, 600, 420, 300, None, None)])
        one = page["blocks"][0]; two = dict(one, id="b02")
        one["box"] = [560, 480, 800, 720]; two["box"] = [200, 480, 440, 720]; page["blocks"] = [one, two]; page["order"] = ["b01", "b02"]
        s = Setter(conf.FONT)
        def ink(texts):
            out, rep = s.set_page(img, page, texts); a = np.asarray(out.convert("L")) < 128
            m, (ox, oy) = mask_of(one["balloon"]["outline"], a.shape); inside = np.zeros(a.shape, bool); inside[oy:oy + m.shape[0], ox:ox + m.shape[1]] = cv2.erode(m.astype(np.uint8), np.ones((9, 9), np.uint8)) > 0
            return a & inside, rep
        right, r1 = ink({"b01": {"text": "The first block of the balloon says this", "lang": "en"}})
        left, r2 = ink({"b02": {"text": "and the second block says that after it", "lang": "en"}})
        both, r3 = ink({"b01": {"text": "The first block of the balloon says this", "lang": "en"}, "b02": {"text": "and the second block says that after it", "lang": "en"}})
        self.assertTrue(r3["blocks"]["b01"]["set"] and r3["blocks"]["b02"]["set"], r3)
        self.assertFalse((right & left).any(), "the two blocks' lines do not lie over each other")
        xs1 = np.nonzero(right.any(axis=0))[0]; xs2 = np.nonzero(left.any(axis=0))[0]
        self.assertGreater(xs1.min(), xs2.max(), "the block on the right is set on the right of the one on the left")
        self.assertEqual(int((both ^ (right | left)).sum()), 0, "and set together they are what each is alone")
        page["blocks"] = [one]; page["order"] = ["b01"]                                                    # a balloon of one block has the whole inside, as before
        alone, _ = ink({"b01": {"text": "The first block of the balloon says this", "lang": "en"}})
        xa = np.nonzero(alone.any(axis=0))[0]; self.assertLess(xa.min(), 500); self.assertGreater(xa.max(), 500)

@conf.need("COMIC_FONT")
class NoPixelInTwoParts(unittest.TestCase):
    """Found on a real page, on three machines (2026-11-14): three groups of columns in one wide box, three text blocks.
    Two had the box's right part for their inside and were kept apart; the third had an inside of its own, the box's
    left part, which lay over the others' by less than a third and so was not counted as sharing: the middle block's
    lines were set into the left block's. Every pixel that two insides hold goes to the block whose text box is the
    nearer, whatever the share, and no line is set wider than its block's part."""
    def page(self):
        img, page = page_with([("b01", "box", 500, 400, 420, 180, None, None)])
        one = page["blocks"][0]
        right = [[330, 226], [914, 226], [914, 574], [330, 574]]; left = [[86, 226], [470, 226], [470, 574], [86, 574]]       # the two insides the reader gave: they share the strip from 330 to 470
        blocks = []
        for bid, box, outline in (("b01", [640, 300, 860, 500], right), ("b02", [380, 300, 460, 500], right), ("b03", [120, 300, 300, 500], left)):
            blocks.append(dict(one, id=bid, box=box, balloon=dict(one["balloon"], outline=outline)))
        page["blocks"] = blocks; page["order"] = ["b01", "b02", "b03"]
        return img, page

    def test_the_parts_of_blocks_whose_insides_lie_over_each_other_share_no_pixel(self):
        from comic.setter import own_parts
        img, page = self.page(); shape = (img.height, img.width); parts = own_parts(page["blocks"], shape); held = np.zeros(shape, np.uint8)
        for bid, (m, (ox, oy)) in parts.items(): held[oy:oy + m.shape[0], ox:ox + m.shape[1]] += m.astype(np.uint8)
        self.assertEqual(int((held > 1).sum()), 0, "pixels in two parts")
        self.assertEqual(int((held > 0).sum()), 829 * 349, "and every pixel of the two insides is in one: %d" % int((held > 0).sum()))
        for b in page["blocks"]:                                                                         # each block keeps its own text box
            m, (ox, oy) = parts[b["id"]]; x1, y1, x2, y2 = b["box"]; self.assertTrue(m[y1 - oy:y2 - oy, x1 - ox:x2 - ox].all(), b["id"])

    def test_long_texts_side_by_side_are_set_each_in_its_part(self):
        img, page = self.page(); s = Setter(conf.FONT)
        texts = {"b01": {"text": "It has a cute name, but in the end it is still", "lang": "en"}, "b02": {"text": "Linux, is it not, after all that", "lang": "en"},
                 "b03": {"text": "You cannot even watch a video on the web with it", "lang": "en"}}
        out, rep = s.set_page(img, page, texts)
        self.assertTrue(all(rep["blocks"][b]["set"] for b in texts), rep["blocks"])
        self.assertEqual(rep["overlap_px"], 0, "lettering of one block over another's")
        def ink(only):
            o, _ = s.set_page(img, page, {only: texts[only]}); a = np.asarray(o.convert("L")) < 128; a[:232] = False; a[568:] = False; a[:, :92] = False; a[:, 908:] = False
            return a
        a, b, c = ink("b01"), ink("b02"), ink("b03")
        self.assertFalse((a & b).any() or (b & c).any() or (a & c).any(), "set alone, each block's lines keep clear of where the others' are set")
        xs = lambda m: np.nonzero(m.any(axis=0))[0]
        self.assertLess(xs(c).max(), xs(b).min()); self.assertLess(xs(b).max(), xs(a).min())

    def test_a_blocks_paint_keeps_out_of_another_blocks_part(self):
        """Found by counting on real pages: the parts shared no pixel, and 121 pixels of one block's new lettering were
        still painted over. A small block had the corner of a large one's balloon for its part; a read line is painted
        across the hull of a block's part, and the hull of a part with a corner cut out of it takes the corner in."""
        from comic.setter import own_parts, paint_of
        img, page = page_with([("b01", "box", 300, 350, 220, 270, None, None)]); one = page["blocks"][0]; d = ImageDraw.Draw(img)
        inside = [[86, 86], [514, 86], [514, 614], [86, 614]]
        big = dict(one, id="b01", box=[120, 220, 360, 560], balloon=dict(one["balloon"], outline=inside), line_boxes=[[120, 222, 430, 256], [120, 272, 400, 306], [120, 322, 360, 356]])
        small = dict(one, id="b02", box=[350, 130, 480, 262], balloon=dict(one["balloon"], outline=inside), line_boxes=[[352, 224, 470, 254]])
        page["blocks"] = [big, small]; page["order"] = ["b01", "b02"]
        d.rectangle([124, 226, 340, 252], fill=(0, 0, 0)); d.rectangle([356, 228, 466, 250], fill=(0, 0, 0))     # the large block's first line, and the small block's line beside it at the same height
        rgb = np.asarray(img); gray = np.asarray(img.convert("L")); parts = own_parts(page["blocks"], gray.shape)
        m, (ox, oy) = parts["b01"]; m2, (ox2, oy2) = parts["b02"]; self.assertFalse((m & m2).any()); self.assertTrue(m2[240 - oy2, 420 - ox2], "the small block's line stands in its own part")
        self.assertTrue((paint_of(rgb, gray, big, m, ox, oy)[0] & m2).any(), "without the others named, the large block's paint takes the small block's line: the case")
        paint = paint_of(rgb, gray, big, m, ox, oy, m2)[0]
        self.assertFalse((paint & m2).any()); self.assertTrue(paint[240 - oy, 200 - ox], "its own old lettering is painted")
        out, rep = Setter(conf.FONT).set_page(img, page, {"b02": {"text": "Hey.", "lang": "en"}, "b01": {"text": "I knew I would have to help with the install.", "lang": "en"}})
        self.assertTrue(rep["blocks"]["b01"]["set"] and rep["blocks"]["b02"]["set"], rep["blocks"]); self.assertEqual(rep["overlap_px"], 0)

@conf.need("COMIC_CJK_FONT")
class SetCJK(unittest.TestCase):
    def setter(self): return Setter(conf.CJK)

    def test_columns_set_and_read_back(self):
        img, page = one_oval(rx=190, ry=260); text = "明日の朝は七時に、駅前へ集合です"
        out, rep = self.setter().set_page(img, page, {"b01": {"text": text, "lang": "ja", "vertical": True}})
        r = rep["blocks"]["b01"]; self.assertTrue(r["set"], r); self.assertTrue(r["vertical"]); self.assertEqual("".join(r["lines"]), text); self.assertGreaterEqual(len(r["lines"]), 2)
        cols = ink_columns(out, page["blocks"][0]); self.assertEqual(len(cols), len(r["lines"]), (cols, r["lines"]))               # as many columns of ink as the report has
        for (x1, x2, y1, y2), line in zip(cols, r["lines"]):                                                                    # from the right, each as long as its line's cells
            self.assertLess(abs((y2 - y1) - r["px"] * len(vertical.cells(line))), 0.75 * r["px"], (line, y2 - y1, r["px"]))
        tops = [c[2] for c in cols]; self.assertLess(max(tops) - min(tops), 0.5 * r["px"])                                 # the columns hang from one line
        a, b = np.asarray(img), np.asarray(out); m, (ox, oy) = mask_of(page["blocks"][0]["balloon"]["outline"], a.shape); inside = np.zeros(a.shape[:2], bool); inside[oy:oy + m.shape[0], ox:ox + m.shape[1]] = m
        self.assertTrue((a[~inside] == b[~inside]).all(), "set changed pixels outside the balloon")
        for line in r["lines"][1:]: self.assertNotIn(line[0], kinsoku.NO_START)
        self.assertEqual(rep["page_px"], round(1.0 * 36.0 / 0.88))                                                               # the original's glyph, as our font fills its size

    def test_a_korean_original_is_set_larger_than_its_own_glyphs_into_japanese_and_chinese(self):
        """Hangul is lettered smaller than Chinese characters or kana in the same balloons: the Korean edition's glyphs
        measured 0.78 of the Chinese edition's over 20 pages of one comic and 0.80 over 10 pages of another, by two
        letterers (2026-11-14). A page set "as large as the original's glyphs" from a Korean original came out a fifth
        too small beside the same pages set from the English or the Chinese edition."""
        img, page = one_oval(rx=330, ry=330); page["blocks"][0]["text"] = "안녕하세요"
        _, rep = self.setter().set_page(img, page, {"b01": {"text": "はい", "lang": "ja"}}); self.assertEqual(rep["page_px"], round(1.25 * 36.0 / 0.88))
        _, rep = self.setter().set_page(img, page, {"b01": {"text": "好", "lang": "zh"}}); self.assertEqual(rep["page_px"], round(1.25 * 36.0 / 0.95))
        _, rep = self.setter().set_page(img, page, {"b01": {"text": "네", "lang": "ko"}}); self.assertEqual(rep["page_px"], round(1.0 * 36.0 / 0.93))       # hangul for hangul: as large as the original
        page["blocks"][0]["text"] = "你好"                                                                                             # Chinese characters or kana for each other: as before
        _, rep = self.setter().set_page(img, page, {"b01": {"text": "はい", "lang": "ja"}}); self.assertEqual(rep["page_px"], round(1.0 * 36.0 / 0.88))

    def test_a_page_is_not_held_under_the_size_that_reads_by_small_original_lettering(self):
        """The cap by the original's glyphs stops at the smallest size Chinese, Japanese and Korean read at (20 px on a page
        2000 px high): under it every line would be small, and none could be made larger by being said shorter."""
        img, page = one_oval(rx=330, ry=330); page["blocks"][0]["glyph_px"] = 9.0; page["blocks"][0]["cell_px"] = 12.0; page["dialogue_glyph_px"] = 9.0
        _, rep = self.setter().set_page(img, page, {"b01": {"text": "はい", "lang": "ja"}})
        self.assertEqual(rep["page_px"], round(20 * 1500 / 2000.0)); self.assertEqual(rep["blocks"]["b01"]["px"], 15)                # the page is 1500 px high; the glyphs alone would give 10
        page["blocks"][0]["glyph_px"] = 36.0; page["blocks"][0]["cell_px"] = 46.0; page["dialogue_glyph_px"] = 36.0
        _, rep = self.setter().set_page(img, page, {"b01": {"text": "はい", "lang": "ja"}}); self.assertEqual(rep["page_px"], round(36.0 / 0.88))   # a page whose lettering reads is held to it as before

    @conf.need("COMIC_MODELS")
    def test_the_reader_reads_the_columns_back(self):
        from comic import soundwords
        img, page = one_oval(rx=190, ry=260); text = "明日の朝は七時に駅前へ集合です"
        out, rep = self.setter().set_page(img, page, {"b01": {"text": text, "lang": "ja", "vertical": True}}); self.assertTrue(rep["blocks"]["b01"]["set"])
        cols = ink_columns(out, page["blocks"][0]); box = [min(c[0] for c in cols) - 8, min(c[2] for c in cols) - 8, max(c[1] for c in cols) + 8, max(c[3] for c in cols) + 8]
        _, reader = conf.loaded(); self.assertEqual(soundwords.letters(reader(out.crop(box))), soundwords.letters(text))

    def test_the_marks_of_a_column_stand_where_vertical_text_puts_them(self):
        img, page = one_oval(rx=120, ry=420)
        out, rep = self.setter().set_page(img, page, {"b01": {"text": "はい、ノー。", "lang": "ja", "vertical": True}}); r = rep["blocks"]["b01"]; self.assertEqual(r["lines"], ["はい、ノー。"])
        (x1, x2, y1, y2), = ink_columns(out, page["blocks"][0]); px = r["px"]; g = np.asarray(out.convert("L")); xc = (x1 + x2) / 2.0
        # the cells from the column's top: は い 、 ノ ー 。 ; the column's first ink is は's, a little under its cell's top
        cell = lambda i: g[int(y1 - 0.08 * px + i * px):int(y1 - 0.08 * px + (i + 1) * px), int(xc - px / 2):int(xc + px / 2)] < 128
        for i in (2, 5):                                                                                                         # the comma and the full stop: upper right of the cell
            ys, xs = np.nonzero(cell(i)); self.assertGreater(xs.mean(), 0.55 * px, i); self.assertLess(ys.mean(), 0.45 * px, i)
        ys, xs = np.nonzero(cell(4)); self.assertGreater(np.ptp(ys), 2.5 * np.ptp(xs))                                           # the long mark stands upright in a column

    def test_lines_of_japanese_and_chinese(self):
        for text, lang in (("明日の朝は七時に、駅前へ集合です。「はい！」", "ja"), ("明天早上七点，在车站前集合。“好的！”", "zh-Hans")):
            img, page = one_oval()
            out, rep = self.setter().set_page(img, page, {"b01": {"text": text, "lang": lang}}); r = rep["blocks"]["b01"]
            self.assertTrue(r["set"], r); self.assertNotIn("vertical", r); self.assertEqual("".join(r["lines"]), text); self.assertNotIn("hyphenation", r)
            for i, line in enumerate(r["lines"]):
                if i: self.assertNotIn(line[0], kinsoku.NO_START, r["lines"])
                if i < len(r["lines"]) - 1: self.assertNotIn(line[-1], kinsoku.NO_END, r["lines"])
            self.assertEqual(text.upper(), "".join(r["lines"]).upper()); self.assertNotEqual(out.tobytes(), img.tobytes())

    def test_a_korean_balloon(self):
        st = self.setter()
        if st.lacks("한국어"): self.skipTest("COMIC_CJK_FONT has no hangul")
        img, page = one_oval(); text = "내일 아침 일곱 시에 역 앞에서 모입시다."
        out, rep = st.set_page(img, page, {"b01": {"text": text, "lang": "ko", "vertical": True}}); r = rep["blocks"]["b01"]
        self.assertTrue(r["set"], r); self.assertNotIn("vertical", r); self.assertEqual(" ".join(r["lines"]), text); self.assertEqual(r["hyphenations"], 0)     # lines, whatever was asked; whole words
        img, page = one_oval(rx=90, ry=300)                                                                                          # a balloon too narrow for the long word: it breaks between syllables, with no hyphen
        out, rep = st.set_page(img, page, {"b01": {"text": "에이다이대학병원에서", "lang": "ko"}}); r = rep["blocks"]["b01"]
        if r["set"] and len(r["lines"]) > 1: self.assertEqual("".join(r["lines"]), "에이다이대학병원에서"); self.assertGreaterEqual(r["hyphenations"], 1); self.assertFalse(any("-" in l for l in r["lines"]))

    def test_paper_and_ink(self):
        yellow, black, white = (250, 228, 120), (18, 18, 18), (255, 255, 255)
        img, page = page_with([("b01", "box", 300, 400, 200, 260, yellow, (0, 0, 0)), ("b02", "oval", 700, 1000, 200, 300, black, white)], ground=(150, 170, 150))
        d = ImageDraw.Draw(img); f = self.setter().font(40, plain=True)
        vertical.draw(img, f, 40, "むかしむかし", 300, 280, (0, 0, 0)); vertical.draw(img, f, 40, "だれだ！", 700, 900, white)                       # the old lettering
        texts = {"b01": {"text": "很久很久以前", "lang": "zh-Hans", "vertical": True}, "b02": {"text": "是谁！", "lang": "zh-Hans", "vertical": True}}
        out, rep = self.setter().set_page(img, page, texts); self.assertTrue(all(v["set"] for v in rep["blocks"].values()), rep)
        b = np.asarray(out).astype(int)
        for blk, paper, ink in ((page["blocks"][0], yellow, (0, 0, 0)), (page["blocks"][1], black, white)):
            m, (ox, oy) = mask_of(blk["balloon"]["outline"], b.shape); px = b[oy:oy + m.shape[0], ox:ox + m.shape[1]][m]
            near_paper = (np.abs(px - np.array(paper)).max(axis=1) < 40); near_ink = (np.abs(px - np.array(ink)).max(axis=1) < 40)
            self.assertGreater(near_paper.mean(), 0.85); self.assertGreater(near_ink.mean(), 0.003)                               # the paper kept, the new lettering in the ink
            d2 = np.array(ink) - np.array(paper); t = ((px - np.array(paper)) * d2).sum(axis=1) / float((d2 * d2).sum())
            self.assertLess((np.abs((px - np.array(paper)) - np.outer(t, d2)).max(axis=1) > 60).mean(), 0.002)                    # nothing of another colour: no white patch on the yellow, none on the black
        plain = {**page, "blocks": [{**x, "balloon": {k: v for k, v in x["balloon"].items() if k not in ("paper", "ink")}} for x in page["blocks"]]}
        out2, rep2 = self.setter().set_page(img, plain, texts); c = np.asarray(out2).astype(int)                                  # without the fields: white paint and black ink, as before
        self.assertTrue(rep2["blocks"]["b01"]["set"]); self.assertFalse(rep2["blocks"]["b02"]["set"])                             # and the black balloon, with no paper named, is left as it is
        self.assertEqual(rep2["blocks"]["b02"]["why_not"], "the balloon is dark and the page names no paper and ink for it: left as it is")
        m2, (ox2, oy2) = mask_of(page["blocks"][1]["balloon"]["outline"], c.shape); a0 = np.asarray(img).astype(int)
        self.assertTrue((a0[oy2:oy2 + m2.shape[0], ox2:ox2 + m2.shape[1]] == c[oy2:oy2 + m2.shape[0], ox2:ox2 + m2.shape[1]]).all())
        m, (ox, oy) = mask_of(page["blocks"][0]["balloon"]["outline"], c.shape); px = c[oy:oy + m.shape[0], ox:ox + m.shape[1]][m]
        self.assertGreater((px.min(axis=1) > 240).mean(), 0.01)

    def test_a_kept_name_is_never_broken_while_the_block_stays_large_enough(self):
        text = "斉藤英二郎先生はもう帰りました"; name = "斉藤英二郎"
        for rx, ry, vertical_ in ((150, 300, False), (160, 190, True)):
            img, page = one_oval(rx=rx, ry=ry)
            _, free = self.setter().set_page(img, page, {"b01": {"text": text, "lang": "ja", "vertical": vertical_}})
            _, rep = self.setter().set_page(img, page, {"b01": {"text": text, "lang": "ja", "vertical": vertical_}}, keep=[name, "東京"])
            r = rep["blocks"]["b01"]; self.assertTrue(r["set"], r); self.assertEqual("".join(r["lines"]), text)
            if "keep_broken" in r: self.assertEqual(r["keep_broken"], [name]); self.assertFalse(any(name in l for l in r["lines"]))
            else: self.assertTrue(any(name in l for l in r["lines"]), r["lines"]); self.assertGreaterEqual(r["px"], self.setter().least(rep["page_px"], page["height"], Way("ja")))
            self.assertEqual(rep["page_px"], free["page_px"])                                                                         # a name does not change the page's size
        img, page = one_oval(rx=70, ry=300)                                                                                          # a balloon the name cannot go into whole at a readable size: broken, and said
        _, rep = self.setter().set_page(img, page, {"b01": {"text": text, "lang": "ja"}}, keep=[name]); r = rep["blocks"]["b01"]
        self.assertTrue(r["set"], r); self.assertEqual(r["keep_broken"], [name])

    @conf.need("COMIC_FONT")
    def test_a_kept_term_in_alphabetic_text(self):
        img, page = one_oval(rx=200, ry=330); text = {"b01": {"text": "He studied at Eiroku University for six years.", "lang": "en"}}
        _, rep = Setter(conf.FONT).set_page(img, page, text, keep=["Eiroku University", "Tokyo"]); r = rep["blocks"]["b01"]
        self.assertTrue(r["set"], r); self.assertTrue("keep_broken" in r or any("EIROKU UNIVERSITY" in l for l in r["lines"]), r)
        out0, rep0 = Setter(conf.FONT).set_page(img, page, text); out1, rep1 = Setter(conf.FONT).set_page(img, page, text, keep=["Tokyo"])
        self.assertEqual(out0.tobytes(), out1.tobytes()); self.assertEqual(rep0, rep1)                                               # a kept string the text does not hold changes nothing

    def test_art_that_comes_into_the_inside_is_left(self):
        """One paint for every target: what is painted over is the old lettering, whatever is set in its place."""
        for lang, text in (("zh-Hans", "你好"), ("en", "Hello"), ("ko", "안녕")):
            img, page = one_oval(); d = ImageDraw.Draw(img); f = self.setter().font(40, plain=True)
            vertical.draw(img, f, 40, "こんにちは", 410, 500, (0, 0, 0))                                                               # the old lettering, left of the middle
            d.rectangle([236, 560, 330, 590], fill=(0, 0, 0))                                                                       # art: a bar that comes in over the balloon's left edge
            self.assertGreater((np.asarray(img.convert("L"))[500:700, 390:430] < 128).sum(), 500)
            out, rep = self.setter().set_page(img, page, {"b01": {"text": text, "lang": lang, "vertical": True}}); self.assertTrue(rep["blocks"]["b01"]["set"], rep)
            after = np.asarray(out.convert("L"))
            self.assertTrue((after[565:586, 262:325] < 60).all(), "the bar inside the balloon was painted over (" + lang + ")")       # the art is left as it was
            self.assertEqual(int((after[490:710, 385:435] < 200).sum()), 0, "the old lettering is still there (" + lang + ")")        # the old lettering is painted over
            self.assertGreater((after[500:700, 440:560] < 128).sum(), 200)                                                           # and the new stands in the middle

    def test_lettering_drawn_into_the_art_is_left_as_art(self):
        img, page = page_with([("b01", "oval", 300, 500, 230, 300, None, None), ("b02", "oval", 720, 1050, 230, 300, None, None)]); d = ImageDraw.Draw(img); f = self.setter().font(40, plain=True)
        for y0, y1 in ((430, 520), (560, 470), (610, 590)): d.line([(60, y0), (540, y1)], fill=(0, 0, 0), width=16)                # strokes drawn through the first balloon, from outside it
        vertical.draw(img, f, 40, "こんにちは", 720, 950, (0, 0, 0))                                                                   # ordinary lettering in the second
        gray = np.asarray(img.convert("L")); rgb = np.asarray(img); shares = []
        for blk in page["blocks"]:
            m, (ox, oy) = mask_of(blk["balloon"]["outline"], gray.shape); got = paint_of(rgb, gray, blk, m, ox, oy); shares.append(got[3]); self.assertLess(got[4], 0.5)
        self.assertGreater(shares[0], 3 * DRAWN); self.assertEqual(shares[1], 0.0)                                                   # the guard's share: large for the drawn strokes, nothing for lettering on its paper
        for lang, text in (("en", "Hello there"), ("zh-Hans", "你好")):
            out, rep = self.setter().set_page(img, page, {"b01": {"text": text, "lang": lang}, "b02": {"text": text, "lang": lang}})
            r = rep["blocks"]["b01"]; self.assertFalse(r["set"]); self.assertEqual(r["why_not"], "the lettering is drawn into the art: left as art")
            self.assertTrue(rep["blocks"]["b02"]["set"], rep)
            m, (ox, oy) = mask_of(page["blocks"][0]["balloon"]["outline"], gray.shape); a, b = np.asarray(img), np.asarray(out)
            self.assertTrue((a[oy:oy + m.shape[0], ox:ox + m.shape[1]] == b[oy:oy + m.shape[0], ox:ox + m.shape[1]]).all(), "a block left as art was painted or set")
            self.assertEqual(rep["page_px"], rep["blocks"]["b02"]["px"])                                                             # it has no say in the page's size

    @conf.need("COMIC_FONT")
    def test_a_font_without_the_letters_says_so(self):
        img, page = one_oval()
        _, rep = Setter(conf.FONT).set_page(img, page, {"b01": {"text": "これは日本語です", "lang": "ja", "vertical": True}})
        self.assertFalse(rep["blocks"]["b01"]["set"]); self.assertIn("no letter for", rep["blocks"]["b01"]["why_not"]); self.assertIn("こ", rep["blocks"]["b01"]["why_not"])
        _, rep = Setter(conf.FONT, more_fonts=[conf.CJK]).set_page(img, page, {"b01": {"text": "これは日本語です", "lang": "ja", "vertical": True}})
        self.assertTrue(rep["blocks"]["b01"]["set"]); self.assertTrue(rep["blocks"]["b01"]["other_font"])                          # set in the first font that has the letters

    def test_a_run_of_marks_is_closed_up_in_a_line(self):
        st = self.setter(); f = st.font(40, plain=True); t = Tight(f, 40)
        self.assertEqual(t.getlength("小心！！！"), f.getlength("小心") + 3 * 20.0); self.assertEqual(t.getlength("小心！"), f.getlength("小心！")); self.assertEqual(t.getlength("何？！だ"), f.getlength("何だ") + 2 * 20.0)
        pieces, a, b = st.pieces("小心！！！这", 40); self.assertEqual([p for _, p in pieces], ["小心", "！", "！", "！", "这"])
        xs = [x for x, _ in pieces]; self.assertEqual([round(xs[i + 1] - xs[i]) for i in (1, 2)], [20, 20]); self.assertLess(b, f.getlength("小心！！！这") - 50)     # half an em a mark: the line is 60 px shorter
        img, page = one_oval(rx=300, ry=200); out, rep = st.set_page(img, page, {"b01": {"text": "小心！！！", "lang": "zh-Hans"}}); self.assertEqual(rep["blocks"]["b01"]["lines"], ["小心！！！"])
        m, (ox, oy) = mask_of(page["blocks"][0]["balloon"]["outline"], (img.height, img.width)); ink = (np.asarray(out.convert("L"))[oy:oy + m.shape[0], ox:ox + m.shape[1]] < 128) & m
        xs = np.nonzero(ink.sum(axis=0))[0]; self.assertLess(xs[-1] - xs[0], 3.6 * rep["blocks"]["b01"]["px"])                       # two characters and three marks in under 3.6 em (5 em before)

    def test_the_smallest_size_given_for_a_line_end(self):
        st = self.setter()
        self.assertEqual(st.least(28, 2000, Way("ko")), 22); self.assertEqual(st.least(24, 2000, Way("zh")), 20); self.assertEqual(st.least(24, 3000, Way("ja")), 30)     # four fifths of the page's size; 20 px on a 2000-px page
        self.assertEqual(st.least(24, 2000, None), 19); self.assertEqual(st.least(9, 500, Way("ja")), 8)                                                                   # alphabetic text: the share only; never under the smallest size

    def test_ways(self):
        self.assertTrue(Way("ja", True).vertical); self.assertTrue(Way("zh-Hant", True).vertical); self.assertFalse(Way("ko", True).vertical); self.assertFalse(Way("ja").vertical)

@conf.need("COMIC_MODELS", "COMIC_CJK_FONT")
class ServeKeep(unittest.TestCase):
    def test_keep_and_vertical_reach_the_setter_through_serve(self):
        """The long-lived mode, started as the program starts it: a set request with `keep` and a text with `vertical`."""
        text = "斉藤英二郎先生はもう帰りました"; name = "斉藤英二郎"
        with tempfile.TemporaryDirectory() as tmp:
            img, page = one_oval(rx=160, ry=190); src = os.path.join(tmp, "page.png"); img.save(src)
            narrow, npage = one_oval(rx=70, ry=300); nsrc = os.path.join(tmp, "narrow.png"); narrow.save(nsrc)
            proc = subprocess.Popen([sys.executable, "-I", "-B", os.path.join(conf.ROOT, "comic_helper.py"), "serve", "--models", conf.MODELS, "--threads", str(conf.THREADS), "--font", conf.CJK],
                                    cwd=tmp, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, encoding="utf-8")
            try:
                def ask(obj):
                    proc.stdin.write(json.dumps(obj, ensure_ascii=False) + "\n"); proc.stdin.flush(); return json.loads(proc.stdout.readline())
                self.assertTrue(json.loads(proc.stdout.readline())["ready"])
                texts = {"b01": {"text": text, "lang": "ja", "vertical": True}}
                r = ask({"id": 1, "op": "set", "image": src, "page": page, "texts": texts, "keep": [name, "東京"], "out": os.path.join(tmp, "a.png")})
                self.assertTrue(r["ok"], r); b = r["report"]["blocks"]["b01"]; self.assertTrue(b["set"]); self.assertTrue(b["vertical"])
                self.assertTrue(any(name in l for l in b["lines"]), b["lines"]); self.assertNotIn("keep_broken", b)                       # the name stands whole in one column
                direct, rep = Setter(conf.CJK).set_page(img, page, texts, keep=[name, "東京"]); self.assertEqual(rep["blocks"]["b01"]["lines"], b["lines"])     # as the setter gives it when called directly
                r = ask({"id": 2, "op": "set", "image": nsrc, "page": npage, "texts": {"b01": {"text": text, "lang": "ja"}}, "keep": [name], "out": os.path.join(tmp, "b.png")})
                self.assertTrue(r["ok"], r); self.assertEqual(r["report"]["blocks"]["b01"]["keep_broken"], [name])                         # a balloon too narrow for the name: broken, and said
                r = ask({"id": 3, "op": "set", "image": src, "page": page, "texts": texts, "out": os.path.join(tmp, "c.png")}); self.assertTrue(r["ok"], r)     # keep is optional
                self.assertTrue(ask({"op": "quit"})["bye"]); proc.wait(timeout=30)
            finally:
                if proc.poll() is None: proc.terminate()                                                                             # by its own process handle, never by name
                proc.stdin.close(); proc.stdout.close()

# The page below as the setter drew it BEFORE Chinese, Japanese and Korean were added (the package at 453d617c), by the
# machine it was drawn on: Pillow's version, its text layout library and FreeType decide the pixels, so the digest is
# held only against the same three. On another machine the comparison with it is skipped and the two others still run.
# The page's balloon holds nothing to paint over, so the digest pins the alphabetic LETTERING (the line ends, the
# size, the glyphs), which is what it was. The PAINT is not what it was: since 2026-11-14 it is one rule for every
# target (it leaves the outline and the art that reach into a balloon; test_art_that_comes_into_the_inside_is_left
# and test_lettering_drawn_into_the_art_is_left_as_art pin it), and pages with old lettering changed with it.
# Since 2026-11-14 every font is opened with Pillow's basic layout, so the page no longer depends on whether the machine
# has raqm: the digest is keyed by Pillow, FreeType and the font alone and runs on a machine without raqm too. (With
# raqm the page was fb2932b5…; the basic layout sets the same four lines at the same 36 px, the letters a little apart.)
BEFORE = {"Pillow 13.0.0, FreeType 2.14.3, font 3e7e5fccfd7e": "9f3765d526c8ec08073520cc22d2cde35ce08cf86e32176c3deded1c20b48752"}

@conf.need("COMIC_FONT")
class AlphabeticLettering(unittest.TestCase):
    TEXT = {"b01": {"text": "We all meet at seven tomorrow morning, immediately. Understood?", "lang": "en"}}

    def test_the_lettering_is_what_it_was(self):
        img, page = one_oval()
        out, rep = Setter(conf.FONT).set_page(img, page, self.TEXT); self.assertTrue(rep["blocks"]["b01"]["set"])
        with open(conf.FONT, "rb") as f: font = hashlib.sha256(f.read()).hexdigest()[:12]
        key = f"Pillow {PIL.__version__}, FreeType {features.version('freetype2')}, font {font}"
        if key not in BEFORE: self.skipTest("no digest of the page as it was for " + key)
        self.assertEqual(hashlib.sha256(out.tobytes()).hexdigest(), BEFORE[key])

    def test_every_font_is_opened_with_the_layout_every_machine_has(self):
        """Found by setting one comic on three machines (2026-11-14): with no layout named, Pillow shapes text with raqm
        where the system has FriBiDi and with its own basic layout where it has not, and the kerning differs: the same
        comic came out at 31 px on one machine and 30 px on two. Every font is opened with the basic layout, as the
        Chinese, Japanese and Korean setting always was, so a comic is lettered the same everywhere."""
        from PIL import ImageFont
        s = Setter(conf.FONT)
        self.assertEqual(s.font(40).layout_engine, ImageFont.Layout.BASIC); self.assertEqual(s.font(40, plain=True).layout_engine, ImageFont.Layout.BASIC)
        self.assertIs(s.font(40), s.font(40))

    def test_what_was_added_does_not_reach_alphabetic_text(self):
        img, page = one_oval(); base, rep0 = Setter(conf.FONT).set_page(img, page, self.TEXT)
        asked = {"b01": dict(self.TEXT["b01"], vertical=True)}                                                                   # columns asked for an alphabetic language: lines, as ever
        out, rep = Setter(conf.FONT).set_page(img, page, asked); self.assertEqual(out.tobytes(), base.tobytes()); self.assertEqual(rep, rep0)
        white = {**page, "blocks": [{**b, "balloon": {**b["balloon"], "paper": [255, 255, 255], "ink": [0, 0, 0]}} for b in page["blocks"]]}
        out, _ = Setter(conf.FONT).set_page(img, white, self.TEXT); self.assertEqual(out.tobytes(), base.tobytes())              # white paper and black ink named: the same page
        if conf.CJK:
            out, rep = Setter(conf.FONT, more_fonts=[conf.CJK]).set_page(img, page, self.TEXT); self.assertEqual(out.tobytes(), base.tobytes()); self.assertEqual(rep, rep0)

if __name__ == "__main__":
    unittest.main()
