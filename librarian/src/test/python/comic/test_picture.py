"""The numbered picture a model is shown, and the second font: neither needs the models. The page is drawn here."""
import math, unittest
from PIL import Image, ImageDraw
import conf
from comic.picture import numbered, RED
from comic.setter import Setter, as_set

def blank_page(w=1000, h=1500):
    """A white page with one oval balloon, and the block a read would give for it."""
    img = Image.new("RGB", (w, h), (255, 255, 255)); d = ImageDraw.Draw(img)
    cx, cy, rx, ry = 500, 600, 260, 330
    d.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], fill=(255, 255, 255), outline=(0, 0, 0), width=4)
    outline = [[round(cx + (rx - 6) * math.cos(a * math.pi / 30)), round(cy + (ry - 6) * math.sin(a * math.pi / 30))] for a in range(60)]
    block = {"id": "b01", "box": [420, 380, 580, 820], "score": 0.9, "kind": "speech", "balloon": {"found": "balloon", "closed": True, "outline": outline},
             "text": "こんにちは", "readings": [], "numbers": [], "sound": None, "glyph_px": 40.0, "cell_px": 46.0, "look": []}
    return img, {"image": "drawn", "width": w, "height": h, "dialogue_glyph_px": 40.0, "order": ["b01"], "blocks": [block]}

class Picture(unittest.TestCase):
    def test_numbered_boxes(self):
        img, page = blank_page(2000, 3000)
        pic = numbered(img, [("1", [840, 760, 1160, 1640]), ("12", [100, 100, 300, 500])])
        self.assertEqual(pic.size, (867, 1300))                              # the long side 1300, the shape kept
        f = 1300 / 3000.0
        self.assertEqual(pic.getpixel((round(840 * f), round(1200 * f))), RED)   # the box's left edge
        self.assertEqual(pic.getpixel((round(840 * f) + 2, round(760 * f) - 10)), RED)   # its number's tab, above the box
        self.assertEqual(img.getpixel((840, 1200)), (255, 255, 255))         # the page given is not drawn on
        small = numbered(Image.new("RGB", (400, 600), (255, 255, 255)), [("1", [10, 40, 100, 200])])
        self.assertEqual(small.size, (400, 600))                             # a small page is not made larger

@conf.need("COMIC_FONT", "COMIC_MORE_FONT")
class SecondFont(unittest.TestCase):
    def test_a_block_is_set_in_the_first_font_that_has_its_letters(self):
        img, page = blank_page()
        one = Setter(conf.FONT)
        _, rep = one.set_page(img, page, {"b01": {"text": "Привет, доктор!", "lang": "ru"}})
        self.assertFalse(rep["blocks"]["b01"]["set"]); self.assertIn("no letter for", rep["blocks"]["b01"]["why_not"]); self.assertIn("П", rep["blocks"]["b01"]["why_not"])
        two = Setter(conf.FONT, more_fonts=[conf.MORE_FONT])
        out, rep = two.set_page(img, page, {"b01": {"text": "Привет, доктор!", "lang": "ru"}})
        r = rep["blocks"]["b01"]; self.assertTrue(r["set"], r); self.assertTrue(r["other_font"]); self.assertEqual(" ".join(r["lines"]).replace("- ", ""), "ПРИВЕТ, ДОКТОР!")
        self.assertNotEqual(out.tobytes(), img.tobytes())
        _, rep = two.set_page(img, page, {"b01": {"text": "Good morning, doctor.", "lang": "en"}})
        self.assertTrue(rep["blocks"]["b01"]["set"]); self.assertNotIn("other_font", rep["blocks"]["b01"])   # the first font has every letter: it is the one used
        self.assertEqual(one.lacks("Good morning"), []); self.assertEqual(one.lacks("ж"), ["ж"])

    def test_capitals_as_each_alphabet_writes_them(self):
        self.assertEqual(as_set("Καλημέρα, γιατρέ! Πού είναι;"), "ΚΑΛΗΜΕΡΑ, ΓΙΑΤΡΕ! ΠΟΥ ΕΙΝΑΙ;")     # no accent on Greek capitals
        self.assertEqual(as_set("Μαΐου"), "ΜΑΪΟΥ")                                                    # the diaeresis stays
        self.assertEqual(as_set("Здравствуйте, доктор"), "ЗДРАВСТВУЙТЕ, ДОКТОР")
        self.assertEqual(as_set("пʼять", caps=False), "п\u2019ять")                                   # the modifier apostrophe as the mark every font has
        self.assertEqual(as_set("Straße"), "STRASSE"); self.assertEqual(as_set("Good\u00a0morning", caps=False), "Good morning")
        two = Setter(conf.FONT, more_fonts=[conf.MORE_FONT])
        for text in ("Доброго ранку, пʼять хвилин!", "Καλημέρα, γιατρέ! Πού είναι ο ασθενής;", "Добар дан, докторе Ђорђевићу!", "Ѓорѓи, каде е Ќазим?", "Шчыры дзякуй, ў нас усё ёсць"):
            img, page = blank_page()
            _, rep = two.set_page(img, page, {"b01": {"text": text, "lang": "xx"}})
            self.assertTrue(rep["blocks"]["b01"]["set"], (text, rep["blocks"]["b01"]))

if __name__ == "__main__":
    unittest.main()
