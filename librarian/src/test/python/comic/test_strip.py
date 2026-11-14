"""A web comic's strip, drawn here: eight panels with gutters of several colours and heights, one balloon a panel.
cut gives pieces of a page's shape that cover the strip, cuts in the gutters and through no balloon; the pieces read
as pages; join gives the strip back. An ordinary page is not cut."""
import os, tempfile, unittest
import numpy as np
from PIL import Image
import conf, pages_lang
from comic import soundwords, strip
from comic.read import read_page

TEXTS = [["We all meet at seven", "tomorrow morning."], ["Where is the doctor?", "He left an hour ago."], ["Nobody told me", "about the train."],
         ["Bring the red box", "to the kitchen."], ["It rained all night", "on the old roof."], ["My sister sold", "her green bicycle."],
         ["The bridge closes", "at nine tonight."], ["Count the chairs", "before they come."]]

class Shape(unittest.TestCase):
    def test_what_is_a_strip(self):
        self.assertFalse(strip.is_strip(1000, 1400)); self.assertFalse(strip.is_strip(800, 1600)); self.assertFalse(strip.is_strip(2000, 1400))
        self.assertTrue(strip.is_strip(800, 1800)); self.assertTrue(strip.is_strip(800, 12000))

@conf.need("COMIC_MODELS", "COMIC_FONT")
class Strip(unittest.TestCase):
    def test_cut_read_join(self):
        img, balloons, gutters = pages_lang.strip(TEXTS, conf.FONT); m = conf.loaded_models()
        self.assertTrue(strip.is_strip(*img.size))
        with tempfile.TemporaryDirectory() as tmp:
            src = os.path.join(tmp, "episode.png"); img.save(src)
            got = strip.cut(src, os.path.join(tmp, "work"), m.detector())
            self.assertTrue(got["strip"]); self.assertEqual((got["width"], got["height"]), img.size)
            ps = got["pieces"]; self.assertGreaterEqual(len(ps), 2)
            self.assertEqual(ps[0]["top"], 0); self.assertEqual(ps[-1]["top"] + ps[-1]["height"], img.height)
            for a, b in zip(ps, ps[1:]): self.assertEqual(a["top"] + a["height"], b["top"])                  # no gap, no overlap
            for p in ps:
                self.assertLessEqual(p["height"], strip.PIECE * img.width + 1); self.assertTrue(p["file"].endswith(".png"))
                with Image.open(p["file"]) as im: self.assertEqual(im.size, (img.width, p["height"]))
            for p in ps[1:]:
                self.assertFalse(any(bb[1] < p["top"] < bb[3] for bb in balloons), f'a cut at row {p["top"]} goes through a balloon')
                self.assertTrue(any(a <= p["top"] <= b for a, b in gutters), f'a cut at row {p["top"]} is not in a gutter')
            self.assertEqual(set(got["cut_through"]), {"gutter"})
            read = []
            for p in ps:
                with Image.open(p["file"]) as im:
                    read += [soundwords.letters(b["text"]).casefold() for b in read_page(im, m.detector(), m.reader("en"), language="en")["blocks"]]
            for lines in TEXTS:
                self.assertIn(soundwords.letters(" ".join(lines)).casefold(), read)
            out = os.path.join(tmp, "joined.png")
            self.assertEqual(strip.join([{"file": p["file"], "top": p["top"]} for p in ps], got["width"], got["height"], out), out)
            with Image.open(out) as back: self.assertTrue((np.asarray(back.convert("RGB")) == np.asarray(img)).all(), "the joined strip is not the strip")
            jpg = os.path.join(tmp, "joined.jpg"); strip.join(ps, got["width"], got["height"], jpg)
            with Image.open(jpg) as back: self.assertEqual((back.format, back.size), ("JPEG", img.size))

    def test_a_bmp_strip(self):
        img, _, _ = pages_lang.strip(TEXTS[:5], conf.FONT)
        with tempfile.TemporaryDirectory() as tmp:
            src = os.path.join(tmp, "episode.bmp"); img.save(src)
            got = strip.cut(src, os.path.join(tmp, "work"), conf.loaded_models().detector())
            self.assertTrue(got["strip"]); self.assertEqual(sum(p["height"] for p in got["pieces"]), img.height)
            bmps = []
            for p in got["pieces"]:                                                                    # the program's pieces may be BMP too
                with Image.open(p["file"]) as im: im.save(p["file"][:-4] + ".bmp")
                bmps.append({"file": p["file"][:-4] + ".bmp", "top": p["top"]})
            out = os.path.join(tmp, "joined.bmp"); self.assertEqual(strip.join(bmps, got["width"], got["height"], out), out)
            with Image.open(out) as back:
                self.assertEqual(back.format, "BMP"); self.assertTrue((np.asarray(back.convert("RGB")) == np.asarray(img)).all())
            for name, fmt in (("joined.webp", "WEBP"), ("joined.png", "PNG")):
                strip.join(bmps, got["width"], got["height"], os.path.join(tmp, name))
                with Image.open(os.path.join(tmp, name)) as back: self.assertEqual((back.format, back.size), (fmt, img.size))
            with self.assertRaises(ValueError): strip.join(bmps, got["width"], got["height"], os.path.join(tmp, "joined.tiff"))

    def test_a_page_is_not_cut(self):
        img, _ = pages_lang.page([["We all meet at seven", "tomorrow morning."]], conf.FONT)
        with tempfile.TemporaryDirectory() as tmp:
            src = os.path.join(tmp, "page.png"); img.save(src); work = os.path.join(tmp, "work")
            got = strip.cut(src, work, conf.loaded_models().detector())
            self.assertFalse(got["strip"]); self.assertEqual(got["pieces"], []); self.assertFalse(os.path.exists(work))

if __name__ == "__main__":
    unittest.main()
