"""crops: a block's reading crop written at the size a model that sees is shown it. No model is needed: the page and
its page dict are made here."""
import os, tempfile, unittest
from PIL import Image, ImageDraw
import conf
from comic.crops import crops, prepared

def page():
    img = Image.new("RGB", (1200, 1700), (255, 255, 255)); d = ImageDraw.Draw(img)
    d.rectangle([100, 100, 400, 220], outline=(0, 0, 0), width=3); d.rectangle([100, 400, 1100, 1650], fill=(200, 60, 60))
    blocks = [{"id": "b01", "kind": "speech", "box": [110, 110, 390, 210], "crop": [104, 104, 396, 216]},          # small: enlarged
              {"id": "b02", "kind": "outside", "box": [100, 400, 1100, 1650], "crop": [100, 400, 1100, 1650]},      # in between: left as it is
              {"id": "b03", "kind": "speech", "box": [500, 100, 560, 130]},                                         # no "crop": the box with its margin
              {"id": "b04", "kind": "sound", "box": [600, 100, 700, 200], "crop": [600, 100, 700, 200]}]
    return img, {"image": "drawn", "width": 1200, "height": 1700, "blocks": blocks}

class Crops(unittest.TestCase):
    def test_sizes(self):
        self.assertEqual(prepared(Image.new("RGB", (292, 112))).size, (1024, 393))            # long side to 1024
        self.assertEqual(prepared(Image.new("RGB", (60, 30))).size, (240, 120))               # never more than four times
        self.assertEqual(prepared(Image.new("RGB", (1000, 1250))).size, (1000, 1250))         # between 1024 and 1536: as it is
        self.assertEqual(prepared(Image.new("RGB", (800, 3072))).size, (400, 1536))           # long side down to 1536

    def test_crops_of_a_page(self):
        img, pg = page()
        with tempfile.TemporaryDirectory() as tmp:
            src = os.path.join(tmp, "page.png"); img.save(src); out = os.path.join(tmp, "work", "crops")
            got = crops(src, pg, out)
            self.assertEqual([c["id"] for c in got], ["b01", "b03"])                           # no ids: every speech block, in the page's order
            self.assertEqual(got[0]["size"], [1024, 393]); self.assertEqual(os.path.basename(got[0]["file"]), "b01.png")
            with Image.open(got[0]["file"]) as c:
                self.assertEqual((c.format, c.size), ("PNG", (1024, 393))); self.assertEqual(c.convert("RGB").getpixel((512, 200)), (255, 255, 255))
            self.assertEqual(got[1]["size"], [272, 152])                                       # the box [500,100,560,130] with 4 px of margin, four times
            got = crops(src, pg, out, ids=["b04", "b02"])
            self.assertEqual([c["id"] for c in got], ["b04", "b02"]); self.assertEqual(got[1]["size"], [1000, 1250])
            with Image.open(got[1]["file"]) as c: self.assertEqual(c.convert("RGB").getpixel((500, 600)), (200, 60, 60))
            with self.assertRaises(ValueError) as e: crops(src, pg, out, ids=["b01", "b99"])
            self.assertIn("b99", str(e.exception))

class OnlyThisBlock(unittest.TestCase):
    """Read live (2026-11-14): of seven wrong second readings on hand-lettered pages, six held words of a neighbouring
    balloon or caption that the crop also showed. A crop shows the model this block alone: other blocks' lettering in
    it is covered with the colour around it."""
    def drawn(self):
        img = Image.new("RGB", (900, 700), (236, 228, 200)); d = ImageDraw.Draw(img)              # yellowed paper
        d.rectangle([120, 120, 520, 150], fill=(20, 20, 20)); d.rectangle([120, 170, 480, 200], fill=(20, 20, 20))      # this block's two lines
        d.rectangle([100, 260, 560, 330], fill=(230, 150, 170)); d.rectangle([130, 280, 500, 310], fill=(40, 20, 30))   # a pink caption under it, with its own line
        d.rectangle([560, 110, 800, 140], fill=(20, 20, 20))                                                           # the start of the balloon to the right
        blocks = [{"id": "b01", "kind": "speech", "box": [116, 116, 524, 204], "line_boxes": [[118, 118, 522, 152], [118, 168, 482, 202]], "crop": [100, 100, 640, 340]},
                  {"id": "b02", "kind": "speech", "box": [126, 276, 504, 314], "line_boxes": [[128, 278, 502, 312]], "crop": [110, 260, 520, 330]},
                  {"id": "b03", "kind": "speech", "box": [556, 106, 804, 144], "crop": [540, 90, 820, 160]}]          # no line boxes: its text box
        return img, {"image": "drawn", "width": 900, "height": 700, "blocks": blocks}

    def test_other_blocks_lettering_in_a_crop_is_covered_with_the_colour_around_it(self):
        img, pg = self.drawn()
        with tempfile.TemporaryDirectory() as tmp:
            src = os.path.join(tmp, "page.png"); img.save(src)
            got = crops(src, pg, os.path.join(tmp, "crops"), ids=["b01", "b02"])
            self.assertEqual(got[0]["covered"], 2); self.assertEqual(got[1]["covered"], 0)
            with Image.open(got[0]["file"]) as c:
                c = c.convert("RGB"); f = c.width / 540.0; at = lambda x, y: c.getpixel((round((x - 100) * f), round((y - 100) * f)))
                self.assertLess(sum(at(300, 135)), 100, "this block's own first line stands"); self.assertLess(sum(at(300, 185)), 100, "and its second")
                near = lambda a, b: max(abs(u - v) for u, v in zip(a, b)) <= 12
                self.assertTrue(near(at(300, 295), (230, 150, 170)), "the caption's line is covered with the caption's pink: %s" % (at(300, 295),))
                self.assertTrue(near(at(600, 125), (236, 228, 200)), "the neighbour's line is covered with the paper's colour: %s" % (at(600, 125),))
                self.assertTrue(near(at(300, 270), (230, 150, 170)), "the caption box itself is still there")
            with Image.open(got[1]["file"]) as c: self.assertLess(sum(c.convert("RGB").getpixel((c.width // 2, c.height // 2))), 200, "a crop that holds no other block is the page as it is")

    def test_a_neighbour_whose_box_lies_over_this_blocks_lines_does_not_cover_them(self):
        img, pg = self.drawn()
        pg["blocks"][2]["box"] = [400, 106, 804, 204]                                               # the neighbour's box reaches over this block's lines
        with tempfile.TemporaryDirectory() as tmp:
            src = os.path.join(tmp, "page.png"); img.save(src)
            got = crops(src, pg, os.path.join(tmp, "crops"), ids=["b01"])
            with Image.open(got[0]["file"]) as c:
                c = c.convert("RGB"); f = c.width / 540.0; at = lambda x, y: c.getpixel((round((x - 100) * f), round((y - 100) * f)))
                self.assertLess(sum(at(470, 135)), 100, "this block's own line stands under the neighbour's box"); self.assertGreater(sum(at(600, 125)), 500, "the neighbour's own lettering beside it is covered")

if __name__ == "__main__":
    unittest.main()
