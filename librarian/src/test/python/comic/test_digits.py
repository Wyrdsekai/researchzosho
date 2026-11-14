"""Regression: numbers set sideways in a vertical column. The set is generated here (seeded), with exact truth.
Measured when the fix was built: of 160 sideways numbers the reader alone had 65 right and the fix 151; of 40 controls
with an upright digit none was damaged. The test holds the fix to those numbers."""
import re, unittest
import numpy as np
import conf, pages
from comic import digits
from comic.columns import analyse
from comic.geometry import crop_box

@conf.need("COMIC_MODELS", "COMIC_CJK_FONT")
class SidewaysDigits(unittest.TestCase):
    def test_numbers_right(self):
        _, reader = conf.loaded()
        n = conf.DIGITS_N; side = alone = fixed = ctl = ctl_alone = ctl_fixed = 0
        for img, box, bal, num, control in pages.digit_set(n, conf.CJK, conf.CJK_INDEX):
            gray = np.asarray(img); text = reader(img.crop(crop_box(box, img.width, img.height)))
            a = analyse(gray, {"box": box, "cls": "text_bubble"}, [bal])
            new, _ = digits.fix(gray, a, text, reader)
            ok0 = re.findall(r"\d+", digits.nfkc(text)) == [num]; ok1 = re.findall(r"\d+", digits.nfkc(new)) == [num]
            if control: ctl += 1; ctl_alone += ok0; ctl_fixed += ok1; self.assertFalse(ok0 and not ok1, f"a control was damaged: {text} -> {new}")
            else: side += 1; alone += ok0; fixed += ok1
        print(f"\nsideways numbers: {side}, right alone {alone}, right with the fix {fixed}; controls: {ctl}, right alone {ctl_alone}, right with the fix {ctl_fixed}")
        self.assertGreaterEqual(fixed, round(151 / 160 * side), "fewer sideways numbers right than measured")
        self.assertEqual(ctl_fixed, ctl_alone)

if __name__ == "__main__":
    unittest.main()
