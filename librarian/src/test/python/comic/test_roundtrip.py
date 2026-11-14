"""read -> set on a page the test draws: three speech balloons (plain, furigana, a sideways number) and a drawn
sound word. Checks what read reports and that set touches only the insides of the balloons it sets."""
import re, unittest
import numpy as np
import conf, pages
from comic import digits, soundwords
from comic.balloon import mask_of
from comic.read import read_page
from comic.setter import Setter

@conf.need("COMIC_MODELS", "COMIC_CJK_FONT")
class RoundTrip(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.img = pages.page(conf.CJK, conf.CJK_INDEX)
        det, reader = conf.loaded()
        cls.page = read_page(cls.img, det, reader, name="generated")

    def block(self, wanted):
        for b in self.page["blocks"]:
            if soundwords.letters(b["text"]) == soundwords.letters(wanted): return b
        self.fail(f"no block reads {wanted}: " + " | ".join(b["text"] for b in self.page["blocks"]))

    def test_read(self):
        p = self.page
        self.assertEqual(len(p["blocks"]), 4); self.assertEqual(p["order"], [b["id"] for b in p["blocks"]])
        plain = self.block(pages.PAGE_TEXTS["plain"]); self.assertEqual(plain["kind"], "speech"); self.assertEqual(plain["readings"], [])
        ruby = self.block(pages.PAGE_TEXTS["ruby"])
        self.assertEqual([(r["base"], r["reading"]) for r in ruby["readings"]], [("山田", "やまだ")])
        self.assertEqual(ruby["text"][ruby["readings"][0]["start"]:ruby["readings"][0]["end"]], "山田")
        self.assertNotIn("やまだ", ruby["text"])                              # a reading never leaks into the main text
        num = self.block(pages.PAGE_TEXTS["number"]); self.assertIn("204", digits.nfkc(num["text"])); self.assertIn("digits", num["look"])
        snd = self.block(pages.PAGE_TEXTS["sound"]); self.assertEqual(snd["kind"], "sound"); self.assertTrue(snd["sound"]["why"])
        for b in (plain, ruby, num): self.assertGreaterEqual(len(b["balloon"]["outline"]), 3)
        self.assertLess(p["blocks"][0]["box"][1], p["blocks"][-1]["box"][1])   # the top row is read before the bottom row

    @conf.need("COMIC_FONT")
    def test_set(self):
        texts = {b["id"]: {"text": "We all meet at seven tomorrow morning, immediately.", "lang": "en"} for b in self.page["blocks"]}
        ruby = self.block(pages.PAGE_TEXTS["ruby"]); texts[ruby["id"]] = {"text": "これは日本語です", "lang": "ja"}
        out, rep = Setter(conf.FONT, conf.MODELS + "/hyphenation").set_page(self.img, self.page, texts)
        a, b = np.asarray(self.img.convert("RGB")), np.asarray(out)
        snd = self.block(pages.PAGE_TEXTS["sound"])
        self.assertFalse(rep["blocks"][snd["id"]]["set"]); self.assertIn("sound word", rep["blocks"][snd["id"]]["why_not"])
        self.assertFalse(rep["blocks"][ruby["id"]]["set"]); self.assertIn("no letter for", rep["blocks"][ruby["id"]]["why_not"])     # the Latin font alone has no kana
        inside = np.zeros(a.shape[:2], dtype=bool); n_set = 0
        for blk in self.page["blocks"]:
            r = rep["blocks"][blk["id"]]
            if not r["set"]: continue
            n_set += 1; self.assertGreaterEqual(r["px"], 8); self.assertEqual(r["page_px"], rep["page_px"]); self.assertIsInstance(r["hyphenations"], int)
            m, (ox, oy) = mask_of(blk["balloon"]["outline"], a.shape); inside[oy:oy + m.shape[0], ox:ox + m.shape[1]] |= m
            self.assertTrue((a[oy:oy + m.shape[0], ox:ox + m.shape[1]][m] != b[oy:oy + m.shape[0], ox:ox + m.shape[1]][m]).any())
        self.assertEqual(n_set, 2)
        self.assertTrue((a[~inside] == b[~inside]).all(), "set changed pixels outside the balloons it set")
        self.assertEqual(len({rep["blocks"][k]["px"] for k in rep["blocks"] if rep["blocks"][k]["set"] and not rep["blocks"][k]["own_size"]}), 1)

if __name__ == "__main__":
    unittest.main()
