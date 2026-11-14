"""Reading comics that are not Japanese, on pages the test draws: left-to-right pages in the Latin, Cyrillic and Greek
alphabets, in Korean and in Chinese (lines, and the traditional layout in columns), a coloured balloon, white lettering
on a black balloon, the reading order, and that the Japanese-only steps do not run for them."""
import unittest
import numpy as np
import conf, pages, pages_lang
from comic import balloon, geometry, lines, soundwords
from comic.read import read_page
from comic.reader import system_of, languages, own_letters

def distance(a, b):
    prev = list(range(len(b) + 1))
    for i, c in enumerate(a, 1):
        cur = [i] + [0] * len(b)
        for j, h in enumerate(b, 1): cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (c != h))
        prev = cur
    return prev[-1]

def letters(s): return soundwords.letters(s).casefold()

class Order(unittest.TestCase):
    def test_reading_order_both_ways(self):
        boxes = [[600, 100, 800, 300], [100, 110, 300, 310], [620, 600, 820, 800], [120, 590, 320, 790]]   # top right, top left, bottom right, bottom left
        self.assertEqual(geometry.reading_order(boxes), [0, 1, 2, 3]); self.assertEqual(geometry.reading_order(boxes, rtl=True), [0, 1, 2, 3])
        self.assertEqual(geometry.reading_order(boxes, rtl=False), [1, 0, 3, 2])
        self.assertTrue(geometry.rtl_of("ja")); self.assertTrue(geometry.rtl_of(None)); self.assertFalse(geometry.rtl_of("en")); self.assertFalse(geometry.rtl_of("ko"))
        self.assertFalse(geometry.rtl_of("zh")); self.assertTrue(geometry.rtl_of("zh", vertical=True)); self.assertTrue(geometry.rtl_of("zh-TW", vertical=True))

    def test_languages_and_their_writing_systems(self):
        self.assertEqual([system_of(c) for c in ("ja", None, "en", "pt-BR", "ru", "uk", "el", "ko", "zh", "zh_TW", "sr", "sr-Latn")],
                         ["japanese", "japanese", "latin", "latin", "cyrillic", "cyrillic", "greek", "korean", "chinese", "chinese", "cyrillic", "latin"])
        for c in ("ja", "en", "zh", "ko", "de", "fr", "es", "pt", "it", "ru"): self.assertIn(c, languages())
        with self.assertRaises(ValueError) as e: system_of("ar")
        self.assertIn("'ar'", str(e.exception))

class Lines(unittest.TestCase):
    def test_look_alike_letters_are_put_right(self):
        self.assertEqual(own_letters("HO Xлоп своИ гостЬЯ DNA", "cyrillic"), "НО Хлоп свои гостья DNA")       # a Latin word with a letter Cyrillic lacks stays
        self.assertEqual(own_letters("KAI TO vερό", "greek"), "ΚΑΙ ΤΟ νερό")
        self.assertEqual(own_letters("tout le monde vOUs attend, iPhone McDonald NASA", "latin"), "tout le monde vous attend, iPhone McDonald NASA")
        self.assertEqual(own_letters("HO vOUs", "korean"), "HO vOUs")

    def test_sound_words_by_writing_system(self):
        for text, system, want in (("BZZZT!", "latin", True), ("PFFT", "latin", True), ("NO!", "latin", False), ("BANG! BANG.", "latin", False), ("Бззз", "cyrillic", True),
                                   ("쾅쾅", "korean", True), ("어디야", "korean", False), ("오", "korean", False), ("咚咚咚", "chinese", True), ("你好嗎", "chinese", False), ("哦", "chinese", False), ("ドドド", "japanese", True)):
            self.assertEqual(soundwords.shape_of(text, system), want, (text, system))
        self.assertEqual(soundwords.decide(True, "WHERE IS HE?", 0.9, 1.0, 1.0, "latin"), (False, None))    # crowded hand lettering is still speech
        self.assertTrue(soundwords.decide(True, "AAAAH", 0.9, 1.0, 1.0, "latin")[0]); self.assertTrue(soundwords.decide(False, "BZZZT", None, None, None, "latin")[0])
        self.assertTrue(soundwords.decide(True, "どこだ", 0.9, 1.0, 1.0, "japanese")[0])                    # Japanese: as it was, the outline alone decides
        self.assertTrue(soundwords.dialogue("Where is the doctor?", "latin")); self.assertFalse(soundwords.dialogue("NO!", "latin")); self.assertTrue(soundwords.dialogue("医生现在在哪里", "chinese"))

    def test_columns_or_lines_from_the_line_boxes(self):
        tall = [[100, 10, 140, 200], [50, 10, 90, 200], [0, 10, 40, 50]]                              # two columns and a last column of one glyph
        self.assertEqual(lines.columns(tall), [tall[0], tall[1], tall[2]])                           # right to left, the single glyph kept
        wide = [[10, 10, 300, 40], [10, 50, 280, 80]]
        self.assertEqual(lines.columns(wide), []); self.assertEqual(lines.rows(wide[::-1]), wide)
        grid = [[60, 0, 100, 40], [60, 45, 100, 85], [60, 90, 100, 130], [0, 0, 40, 40]]              # glyph boxes: three above one another, one beside
        self.assertEqual(lines.columns(grid), [grid[0], grid[1], grid[2], grid[3]])
        halves = [[0, 60, 100, 90, [[0, 70], [100, 54], [103, 74], [3, 90]]], [110, 40, 210, 72, [[110, 52], [210, 36], [213, 56], [113, 72]]],
                  [0, 100, 100, 130, [[0, 110], [100, 94], [103, 114], [3, 130]]]]                    # a rising line found in two pieces, and the line under it
        self.assertEqual(lines.rows(halves), [halves[0], halves[1], halves[2]])
        speck = [[10, 10, 300, 40], [100, 20, 106, 26], [10, 50, 280, 80]]
        self.assertEqual(lines.clean(speck), [speck[0], speck[2]])

@conf.need("COMIC_MODELS", "COMIC_FONT", "COMIC_MORE_FONT", "COMIC_CJK_FONT")
class JapaneseColumns(unittest.TestCase):
    """Found on real pages (2026-11-14): 16 of 159 speech blocks of a manga were taken for lines by the gaps in their ink
    (two or three columns ending in ！！, a column of dots), every one of them a block of columns taller than wide, and
    so were lettered in lines when the pages were set in Chinese. The one block truly in lines was a sign four times
    as wide as high."""
    def test_a_japanese_block_is_in_lines_only_when_it_is_wider_than_tall(self):
        from comic.read import japanese_columns
        self.assertTrue(japanese_columns(True, [100, 100, 237, 436])); self.assertTrue(japanese_columns(True, [100, 100, 431, 175]))      # the ink says columns: columns, whatever the shape
        self.assertTrue(japanese_columns(False, [100, 100, 237, 436]), "137 by 336: two columns ending in two marks, whose gaps said lines")
        self.assertTrue(japanese_columns(False, [100, 100, 272, 292]), "172 by 192: four short columns")
        self.assertFalse(japanese_columns(False, [100, 100, 431, 175]), "331 by 75: a sign lettered in a line")
        self.assertFalse(japanese_columns(False, [100, 100, 300, 240]), "200 by 140: wider than tall by more than three tenths, and the ink says lines")

class LinesOfANeighbour(unittest.TestCase):
    """Found on a real page (2026-11-14): the crop a block is read from is grown over lettering its box cuts through,
    and so took in the last rows of the shout standing above it, a block of its own. "NO!" was read in both blocks,
    and translated and set twice."""
    def test_a_line_that_stands_in_another_blocks_box_is_not_this_blocks(self):
        from comic.read import own_lines
        got = {"text": "NO! I cannot, SORRY!", "vertical": False, "sure": 0.9, "lines": [
            {"box": [46, 0, 107, 24], "thick": 24.0, "text": "NO!", "confidence": 0.99},
            {"box": [20, 40, 200, 62], "thick": 22.0, "text": "I cannot,", "confidence": 0.98},
            {"box": [60, 70, 150, 92], "thick": 22.0, "text": "SORRY!", "confidence": 0.9}]}
        cut = [472, 386, 700, 538]; box = [481, 395, 693, 530]; shout = [518, 374, 579, 410]
        mine = own_lines(got, cut, box, [shout], " ")
        self.assertEqual([l["text"] for l in mine["lines"]], ["I cannot,", "SORRY!"]); self.assertEqual(mine["text"], "I cannot, SORRY!"); self.assertEqual(mine["sure"], 0.9)
        self.assertEqual(own_lines(got, cut, box, [[900, 900, 950, 950]], " ")["text"], "NO! I cannot, SORRY!")                  # a block elsewhere takes nothing
        self.assertEqual(own_lines(got, cut, box, [[481, 395, 693, 530]], " ")["text"], "NO! I cannot, SORRY!")                  # nor one that holds no more of the line than this block does
        only = {"text": "NO!", "vertical": False, "sure": 0.99, "lines": [got["lines"][0]]}
        self.assertEqual(own_lines(only, cut, box, [shout], " ")["text"], "NO!", "a block is never left with nothing by this rule")

class ThePaperOfAStripWithItsBorderInTheBox(unittest.TestCase):
    """Read live (2026-11-14): a caption strip at the foot of a panel, heavy hand lettering on yellow, with the panel's
    thick border along its top and bottom inside the text box. Border and lettering together held more of the box
    than the yellow (0.43 against 0.38), so the dark was taken for the paper and the yellow for the ink: the strip
    was painted dark brown and its translation set in yellow. The paper is the colour that closes round the
    lettering: where the two colours hold nearly as much of the box and the first gives no closed inside, the other
    is tried, and taken when its inside is closed and holds lettering."""
    def strip(self):
        from PIL import Image, ImageDraw
        img = Image.new("RGB", (900, 300), (205, 190, 150)); d = ImageDraw.Draw(img)                       # yellowed page
        d.rectangle([40, 100, 860, 160], fill=(30, 25, 15)); d.rectangle([52, 110, 848, 150], fill=(200, 180, 118))   # the strip: a thick dark border round yellow
        d.rectangle([40, 0, 51, 100], fill=(30, 25, 15)); d.rectangle([849, 0, 860, 100], fill=(30, 25, 15))          # the border is the panel's own frame and runs on up the page
        d.rectangle([0, 100, 900, 109], fill=(30, 25, 15)); d.rectangle([0, 151, 900, 160], fill=(30, 25, 15))        # and across it, to the next panel
        for x in range(64, 830, 26):
            d.rectangle([x, 114, x + 9, 146], fill=(30, 25, 15))                                            # heavy lettering, clear of the border
            if x + 26 < 830: d.rectangle([x + 14, 118, x + 21, 143], fill=(240, 225, 170))                  # and the paper is not flat: lighter blots between the letters
        return np.asarray(img.convert("L")), np.asarray(img)

    def test_the_colour_that_closes_round_the_lettering_is_the_paper(self):
        gray, rgb = self.strip(); tb = [54, 104, 846, 156]                                                  # the text box takes the border's inner half in
        self.assertLess(sum(balloon.paper_of(rgb, tb)), 200, "by its share of the box alone the dark is the paper: the case")
        found = balloon.interior(gray, tb, [], rgb, hull=True)
        self.assertIsNotNone(found); self.assertTrue(found["closed"])
        self.assertTrue(all(abs(a - b) <= 12 for a, b in zip(found["paper"], (200, 180, 118))), found["paper"])
        self.assertLess(sum(found["ink"]), 200, found["ink"])

    def test_a_dark_balloon_with_light_lettering_keeps_its_dark_paper(self):
        from PIL import Image, ImageDraw
        img = Image.new("RGB", (900, 300), (120, 160, 120)); d = ImageDraw.Draw(img); d.rectangle([40, 60, 860, 240], fill=(20, 20, 20))
        for y in (100, 150):
            for x in range(80, 800, 24): d.rectangle([x, y, x + 15, y + 30], fill=(240, 240, 240))
        found = balloon.interior(np.asarray(img.convert("L")), [76, 96, 824, 184], [], np.asarray(img), hull=True)
        self.assertIsNotNone(found); self.assertLess(sum(found["paper"]), 120, found["paper"]); self.assertGreater(sum(found["ink"]), 600, found["ink"])

class ATextBoxOverTwoBalloons(unittest.TestCase):
    """Read live (2026-11-14): on hand-lettered pages the page detector gave one text box to two balloons standing
    close (a question and its answer; an order and "we understand!"). The block was read as one text, with the two
    balloons' lines mixed where they stand side by side, its translation was set in the first balloon and the second
    was left as it was. A text box that lies over two balloon insides is a block a balloon."""
    def page(self, gap=True):
        from PIL import Image, ImageDraw
        img = Image.new("RGB", (900, 500), (120, 160, 120)); d = ImageDraw.Draw(img)
        d.ellipse([60, 80, 440, 380], fill=(250, 250, 240), outline=(0, 0, 0), width=4)
        if gap: d.ellipse([470, 120, 840, 360], fill=(250, 250, 240), outline=(0, 0, 0), width=4)
        def line(x0, x1, y):                                                                             # a line of lettering: letters 16 px wide and 22 high
            for x in range(x0, x1 - 15, 22): d.rectangle([x, y, x + 15, y + 22], fill=(10, 10, 10))
        for y in (160, 210, 260): line(130, 372, y)                                                       # three lines in the first balloon
        if gap:
            for y in (190, 240): line(540, 772, y)                                                        # two in the second, at other heights
        rgb = np.asarray(img); return np.asarray(img.convert("L")), rgb

    def test_two_separate_areas_of_paper_are_two_blocks(self):
        from comic.read import over_balloons
        gray, rgb = self.page()
        parts = over_balloons([126, 156, 774, 286], [], gray, rgb)                                         # one text box over both, and no balloon box from the detector
        self.assertEqual(len(parts), 2, parts)
        left, right = sorted(parts, key=lambda b: b[0])
        self.assertLessEqual(left[2], 445); self.assertGreaterEqual(right[0], 465)                          # each within its own balloon
        self.assertLessEqual(left[0], 130); self.assertGreaterEqual(left[2], 370); self.assertLessEqual(right[0], 540); self.assertGreaterEqual(right[2], 770)   # and holding its own lines whole
        self.assertLessEqual(left[1], 160); self.assertGreaterEqual(left[3], 282); self.assertLessEqual(right[1], 190); self.assertGreaterEqual(right[3], 262)

    def test_two_balloon_boxes_of_the_detector_are_two_blocks(self):
        from comic.read import over_balloons
        gray, rgb = self.page()
        parts = over_balloons([126, 156, 774, 286], [[58, 78, 442, 382], [468, 118, 842, 362]], gray, rgb)
        self.assertEqual(len(parts), 2, parts)
        left, right = sorted(parts, key=lambda b: b[0]); self.assertLessEqual(left[2], 445); self.assertGreaterEqual(right[0], 465)

    def test_a_text_box_in_one_balloon_is_one_block(self):
        from comic.read import over_balloons
        gray, rgb = self.page(gap=False)
        self.assertEqual(over_balloons([126, 156, 374, 286], [[58, 78, 442, 382]], gray, rgb), [[126, 156, 374, 286]])
        self.assertEqual(over_balloons([126, 156, 374, 286], [], gray, rgb), [[126, 156, 374, 286]])
        # a text box that reaches out of its balloon over flat ground of another colour, or over paper that holds no lettering, is one block
        self.assertEqual(len(over_balloons([126, 156, 520, 286], [[58, 78, 442, 382]], gray, rgb)), 1)
        gray2, rgb2 = self.page(); rgb2 = rgb2.copy(); rgb2[185:270, 535:775] = (250, 250, 240); gray2 = gray2.copy(); gray2[185:270, 535:775] = 248   # the second balloon empty
        self.assertEqual(len(over_balloons([126, 156, 774, 286], [], gray2, rgb2)), 1)

    def test_what_keeps_one_balloon_one_block(self):
        from comic.read import over_balloons
        gray, rgb = self.page()
        # the second balloon has a text box of its own, which this box only reaches over: its lettering is that block's
        self.assertEqual(over_balloons([126, 156, 774, 286], [], gray, rgb, others=[[536, 186, 774, 266]]), [[126, 156, 774, 286]])
        self.assertEqual(over_balloons([126, 156, 774, 286], [[58, 78, 442, 382], [468, 118, 842, 362]], gray, rgb, others=[[536, 186, 774, 266]]), [[126, 156, 774, 286]])
        # parts that would lose lettering: the box lies over more paper than the two parts hold (a third balloon's, broken into pieces too small to be parts)
        rgb3 = rgb.copy(); gray3 = gray.copy()
        for x in range(130, 770, 40): rgb3[400:440, x:x + 30] = (250, 250, 240); gray3[400:440, x:x + 30] = 248
        self.assertEqual(len(over_balloons([126, 156, 774, 444], [], gray3, rgb3)), 1)
        self.assertEqual(len(over_balloons([126, 156, 774, 396], [], gray3, rgb3)), 2)
        # a strip narrower than the lettering is no balloon: a panel's line through a balloon cuts a sliver of its paper off, with a piece of a letter on it (seen on a manga whose balloons lie over the panels' borders)
        from PIL import Image, ImageDraw
        img = Image.new("RGB", (400, 400), (120, 160, 120)); d = ImageDraw.Draw(img); d.rectangle([40, 40, 360, 360], fill=(250, 250, 240), outline=(0, 0, 0), width=4)
        for y in (100, 150, 200, 250):
            for x in (144, 184, 224): d.rectangle([x, y, x + 29, y + 29], fill=(10, 10, 10))               # lettering 30 px square
            d.rectangle([108, y + 4, 119, y + 15], fill=(10, 10, 10))                                        # and a stroke of each line's first letter, left of the line that cuts it
        d.rectangle([130, 40, 133, 360], fill=(0, 0, 0))                                                    # the panel's line, through the first letter of every line
        rgb4 = np.asarray(img); gray4 = np.asarray(img.convert("L"))
        self.assertEqual(len(over_balloons([100, 96, 258, 284], [[38, 38, 362, 362]], gray4, rgb4)), 1, "the strip is 30 px of a box 158 wide, and the lettering is 30 px")

class OtherLanguages(unittest.TestCase):
    def read(self, texts, language, font, index=0, vertical=False, colours=None, order=None, S=34):
        img, drawn = pages_lang.page(texts, font, index, S=S, vertical=vertical, colours=colours)
        got = conf.loaded_models().reader(language)
        return img, drawn, read_page(img, conf.loaded_models().detector(), got, name="drawn", language=language, order=order)

    def check(self, page, texts, sep=" ", slack=0.06):
        """Every drawn balloon is a speech block read almost to the letter, in the order drawn."""
        blocks = page["blocks"]; self.assertEqual(len(blocks), len(texts), [b["text"] for b in blocks])
        for b, lines in zip(blocks, texts):
            want = letters(sep.join(lines)); have = letters(b["text"])
            self.assertLessEqual(distance(want, have), max(1, round(slack * len(want))), (sep.join(lines), b["text"]))
            self.assertEqual(b["kind"], "speech"); self.assertEqual(b["readings"], []); self.assertEqual(b["numbers"], [])
            self.assertEqual(len(b["balloon"]["paper"]), 3); self.assertEqual(len(b["balloon"]["ink"]), 3); self.assertGreaterEqual(len(b["balloon"]["outline"]), 3)
            self.assertGreater(b["cell_px"], 0)

    def test_latin_alphabet_left_to_right(self):
        texts = [["We all meet at seven", "tomorrow morning."], ["Où est passé le médecin ?", "Il est déjà parti, hélas !"],
                 ["Größere Äpfel für", "fünf Mädchen, bitte!"], ["Não há pão amanhã,", "só café e maçãs."]]
        _, _, page = self.read(texts, "en", conf.FONT, S=30)
        self.check(page, texts); self.assertEqual(page["direction"], "ltr"); self.assertFalse(page["vertical"]); self.assertEqual(page["language"], "en")
        self.assertTrue(all(not b["vertical"] for b in page["blocks"])); self.assertEqual(page["blocks"][0]["lines"], [l for l in texts[0]])
        self.assertLess(page["blocks"][0]["box"][0], page["blocks"][1]["box"][0])                    # the left balloon of the top row comes first
        _, _, back = self.read(texts, "en", conf.FONT, S=30, order="rtl")
        self.assertEqual(back["direction"], "rtl"); self.assertGreater(back["blocks"][0]["box"][0], back["blocks"][1]["box"][0])

    def test_capitals(self):
        texts = [["WHERE IS THE DOCTOR?", "HE LEFT AN HOUR AGO!"], ["I SAID NOTHING", "ABOUT THE MONEY."]]
        _, _, page = self.read(texts, "en", conf.FONT, S=30)
        self.check(page, texts)
        small = self.read([["Where is the doctor?", "He left an hour ago!"]], "en", conf.FONT, S=30)[2]["blocks"][0]
        for b in (page["blocks"][0], small):                                                         # the cell `set` sizes by: about the em of the lettering drawn at 30 px and its leading, in capitals as in small letters
            self.assertGreater(b["cell_px"], 0.95 * 30, b["cell_px"]); self.assertLess(b["cell_px"], 1.7 * 30, b["cell_px"])

    def test_lines_that_rise_to_the_right(self):
        texts = [["WHERE IS THE DOCTOR?", "HE LEFT AN HOUR AGO!", "NOBODY TOLD ME."], ["I SAID NOTHING", "ABOUT THE MONEY", "TO YOUR BROTHER."]]
        img, drawn = pages_lang.page(texts, conf.FONT, S=30, angle=9); m = conf.loaded_models()
        page = read_page(img, m.detector(), m.reader("en"), name="drawn", language="en")
        self.check(page, texts)                                                                       # hand lettering slants: every line is still read whole

    def test_cyrillic_and_greek(self):
        ru = [["Где сейчас доктор?", "Он ушёл час назад."], ["Но я ничего", "не говорил!"]]
        _, _, page = self.read(ru, "ru", conf.MORE_FONT, S=30); self.check(page, ru)
        self.assertFalse(any(c.isascii() and c.isalpha() for b in page["blocks"] for c in b["text"]), [b["text"] for b in page["blocks"]])   # no Latin twin left in a Cyrillic word
        el = [["Πού είναι ο γιατρός;", "Έφυγε πριν από μία ώρα."]]
        _, _, page = self.read(el, "el", conf.MORE_FONT, S=30); self.check(page, el)

    def test_korean(self):
        texts = [["내일 아침 일곱 시에", "모두 여기서 만나요."], ["의사 선생님은", "어디에 계세요?"]]
        _, _, page = self.read(texts, "ko", conf.CJK, conf.CJK_INDEX); self.check(page, texts); self.assertEqual(page["direction"], "ltr")

    def test_chinese_in_lines_and_in_columns(self):
        lines = [["明天早上七点", "大家在这里集合"], ["医生现在在哪里", "他一小时前走了"]]
        _, _, page = self.read(lines, "zh", conf.CJK, conf.CJK_INDEX); self.check(page, lines, sep="")
        self.assertEqual(page["direction"], "ltr"); self.assertFalse(page["vertical"])
        cols = [["明天早上七點", "大家在這裡集合"], ["醫生現在在哪裡", "他一小時前走了"]]
        _, drawn, page = self.read(cols, "zh", conf.CJK, conf.CJK_INDEX, vertical=True)
        self.assertEqual(page["direction"], "rtl"); self.assertTrue(page["vertical"]); self.assertTrue(all(b["vertical"] for b in page["blocks"]))
        self.check(page, cols[::-1], sep="")                                                          # read right to left: the right balloon first

    def test_a_coloured_balloon_and_white_on_black(self):
        texts = [["We all meet at seven", "tomorrow morning."], ["Where is the doctor?", "He left an hour ago."]]
        colours = [((255, 232, 140), (20, 30, 120)), ((0, 0, 0), (255, 255, 255))]
        img, drawn, page = self.read(texts, "en", conf.FONT, S=30, colours=colours); self.check(page, texts)
        for b, (paper, ink), (tb, bb) in zip(page["blocks"], colours, drawn):
            self.assertLessEqual(max(abs(a - c) for a, c in zip(b["balloon"]["paper"], paper)), 12, b["balloon"])
            self.assertLessEqual(max(abs(a - c) for a, c in zip(b["balloon"]["ink"], ink)), 60, b["balloon"])
            xs = [p[0] for p in b["balloon"]["outline"]]; ys = [p[1] for p in b["balloon"]["outline"]]   # the inside is the balloon, not the text box and not the panel
            for got, drew in ((min(xs), bb[0]), (max(xs), bb[2]), (min(ys), bb[1]), (max(ys), bb[3])): self.assertLessEqual(abs(got - drew), 16, (b["balloon"]["outline"][:4], bb))
            self.assertTrue(b["balloon"]["closed"])

    def test_lettering_on_open_ground(self):
        img, drawn = pages_lang.ground_page(conf.FONT); rgb = np.asarray(img); gray = np.asarray(img.convert("L")); p1, p2 = drawn["panels"]
        found = balloon.open_ground(gray, rgb, drawn["narration"], [drawn["sound"]], 30)               # the rule itself, on the narration's own box
        self.assertIsNotNone(found); self.assertEqual(found["how"], "open ground"); self.assertEqual(found["paper"], [255, 255, 255]); self.assertLess(max(found["ink"]), 60)
        (ox, oy), (h, w) = found["origin"], found["filled"].shape; nb = drawn["narration"]
        self.assertLessEqual(ox, nb[0]); self.assertGreaterEqual(ox + w, nb[2]); self.assertLessEqual(oy, nb[1]); self.assertGreaterEqual(oy + h, nb[3])
        self.assertGreater(oy, p1[3]); self.assertLess(oy + h, p2[1])                                  # it stays on the ground between the two panels
        self.assertLessEqual(oy + h, drawn["sound"][1])                                                # and out of the sound word's box
        self.assertIsNone(balloon.open_ground(gray, rgb, drawn["on_art"], [], 30))                     # lettering on hatching is not on flat ground
        self.assertIsNone(balloon.open_ground(gray, rgb, [p1[0] + 30, p1[1] + 200, p1[2] - 30, p1[1] + 300], [], 30))   # nor is a box that holds a balloon's outline
        m = conf.loaded_models(); page = read_page(img, m.detector(), m.reader("en"), name="drawn", language="en")
        def block(key):
            tb = drawn[key]; cx, cy = (tb[0] + tb[2]) / 2, (tb[1] + tb[3]) / 2
            got = [b for b in page["blocks"] if b["box"][0] <= cx <= b["box"][2] and b["box"][1] <= cy <= b["box"][3]]
            self.assertEqual(len(got), 1, (key, [(b["box"], b["text"]) for b in page["blocks"]])); return got[0]
        nar = block("narration"); self.assertIn("train", nar["text"].lower())
        self.assertEqual(nar["kind"], "speech", nar)                                                   # lettered: on open ground, or because the page detector called the ground a balloon
        self.assertTrue(nar["balloon"]["found"] == "open ground" or nar["balloon"]["found"].startswith("balloon"), nar["balloon"]["found"])
        self.assertNotEqual(block("sound")["kind"], "speech")                                          # a sound word drawn on the ground is not lettered
        xs = [p[0] for p in nar["balloon"]["outline"]]; ys = [p[1] for p in nar["balloon"]["outline"]]
        self.assertGreater(min(ys), p1[3]); self.assertLess(max(ys), p2[1])
        from comic.setter import Setter
        out, rep = Setter(conf.FONT).set_page(img, page, {nar["id"]: {"text": "This line was set by the test.", "lang": "en"}})
        self.assertTrue(rep["blocks"][nar["id"]]["set"], rep["blocks"][nar["id"]])
        ch = (np.asarray(img) != np.asarray(out)).any(axis=2); cy, cx = np.nonzero(ch)
        self.assertTrue(ch.any()); self.assertGreaterEqual(cx.min(), min(xs)); self.assertLessEqual(cx.max(), max(xs)); self.assertGreaterEqual(cy.min(), min(ys)); self.assertLessEqual(cy.max(), max(ys))

    def test_the_readers_own_confidence_and_the_reading_crop(self):
        texts = [["We all meet at seven", "tomorrow morning."], ["Where is the doctor?", "He left an hour ago."]]
        _, _, page = self.read(texts, "en", conf.FONT, S=30)
        for b in page["blocks"]:
            self.assertGreater(b["sure"], 0.5); self.assertLessEqual(b["sure"], 1.0)                   # clean lettering: the reader is sure
            c = b["crop"]; self.assertLessEqual(c[0], b["box"][0]); self.assertLessEqual(c[1], b["box"][1]); self.assertGreaterEqual(c[2], b["box"][2] - 1); self.assertGreaterEqual(c[3], b["box"][3] - 1)
        ja = read_page(pages.page(conf.CJK, conf.CJK_INDEX), conf.loaded_models().detector(), conf.loaded_models().reader("ja"), name="generated")
        self.assertTrue(all("sure" not in b and len(b["crop"]) == 4 for b in ja["blocks"]))            # manga-ocr gives no score

    def test_japanese_page_is_read_as_before(self):
        img = pages.page(conf.CJK, conf.CJK_INDEX); m = conf.loaded_models()
        page = read_page(img, m.detector(), m.reader("ja"), name="generated")
        self.assertEqual(page["direction"], "rtl"); self.assertEqual(page["language"], "ja")
        speech = [b for b in page["blocks"] if b["kind"] == "speech"]; self.assertEqual(len(speech), 3)
        self.assertTrue(all(b["vertical"] for b in speech)); self.assertTrue(all(b["balloon"]["paper"] == [255, 255, 255] for b in speech))
        self.assertTrue(all(max(b["balloon"]["ink"]) < 60 for b in speech)); self.assertTrue(any(b["readings"] for b in speech)); self.assertNotIn("lines", speech[0])

if __name__ == "__main__":
    unittest.main()
