"""Which writing system is a comic lettered in? Pages drawn here in each one; kana means Japanese, hangul Korean, han
alone Chinese. The sample's text comes back, and a page without lettering says "none"."""
import os, tempfile, unittest
from PIL import Image
import conf, pages, pages_lang
from comic.script import script_of, kind_of

class Letters(unittest.TestCase):
    def test_kinds(self):
        self.assertEqual([kind_of(c) for c in "あカ漢한aЖλ1!"], ["kana", "kana", "han", "hangul", "latin", "cyrillic", "greek", None, None])

@conf.need("COMIC_MODELS", "COMIC_FONT", "COMIC_MORE_FONT", "COMIC_CJK_FONT")
class Script(unittest.TestCase):
    def ask(self, images):
        with tempfile.TemporaryDirectory() as tmp:
            paths = []
            for k, im in enumerate(images):
                paths.append(os.path.join(tmp, f"p{k}.png")); im.save(paths[-1])
            return script_of(paths, conf.loaded_models())

    def test_every_writing_system(self):
        en = [["We all meet at seven", "tomorrow morning."], ["Where is the doctor?", "He left an hour ago."], ["Nobody told me", "about the train."]]
        cases = [("japanese", True, pages.page(conf.CJK, conf.CJK_INDEX)),
                 ("latin", False, pages_lang.page(en, conf.FONT, S=30)[0]),
                 ("cyrillic", False, pages_lang.page([["Где сейчас доктор?", "Он ушёл час назад."], ["Но я ничего", "не говорил!"]], conf.MORE_FONT, S=30)[0]),
                 ("greek", False, pages_lang.page([["Πού είναι ο γιατρός;", "Έφυγε πριν από μία ώρα."], ["Δεν είπα τίποτα", "για τα χρήματα."]], conf.MORE_FONT, S=30)[0]),
                 ("korean", False, pages_lang.page([["내일 아침 일곱 시에", "모두 여기서 만나요."], ["의사 선생님은", "어디에 계세요?"]], conf.CJK, conf.CJK_INDEX)[0]),
                 ("chinese", False, pages_lang.page([["明天早上七点", "大家在这里集合"], ["医生现在在哪里", "他一小时前走了"]], conf.CJK, conf.CJK_INDEX)[0]),
                 ("chinese", True, pages_lang.page([["明天早上七點", "大家在這裡集合"], ["醫生現在在哪裡", "他一小時前走了"]], conf.CJK, conf.CJK_INDEX, vertical=True)[0])]
        for script, vertical, img in cases:
            got = self.ask([img])
            self.assertEqual(got["script"], script, got); self.assertEqual(got["vertical"], vertical, got)
            self.assertGreaterEqual(got["share"], 0.8, got); self.assertTrue(got["texts"]); self.assertLessEqual(len(got["texts"]), 40)
        got = self.ask([pages_lang.page(en, conf.FONT, S=30)[0]])
        self.assertIn("doctor", " ".join(got["texts"]).lower())                                        # the sample's text, for telling English from French

    def test_no_lettering(self):
        got = self.ask([Image.new("RGB", (800, 1100), (255, 255, 255))])
        self.assertEqual(got["script"], "none"); self.assertEqual(got["texts"], []); self.assertFalse(got["vertical"])

if __name__ == "__main__":
    unittest.main()
