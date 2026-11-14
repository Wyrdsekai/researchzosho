"""The long-lived mode's requests for the other languages, started as the program starts it: the ready line's languages,
read with a language and an order, a language no reader knows, script, cut and join."""
import json, os, subprocess, sys, tempfile, unittest
from PIL import Image
import conf, pages_lang
from test_strip import TEXTS

@conf.need("COMIC_MODELS", "COMIC_FONT")
class JsonLinesLanguages(unittest.TestCase):
    def test_serve(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = os.path.join(tmp, "page.png"); texts = [["We all meet at seven", "tomorrow morning."], ["Where is the doctor?", "He left an hour ago."]]
            pages_lang.page(texts, conf.FONT, S=30)[0].save(src)
            ep = os.path.join(tmp, "ep01.png"); strip_img = pages_lang.strip(TEXTS, conf.FONT)[0]; strip_img.save(ep)
            proc = subprocess.Popen([sys.executable, "-I", "-B", os.path.join(conf.ROOT, "comic_helper.py"), "serve", "--models", conf.MODELS, "--threads", str(conf.THREADS)],
                                    cwd=tmp, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, encoding="utf-8")
            try:
                def ask(obj):
                    proc.stdin.write(json.dumps(obj) + "\n"); proc.stdin.flush(); return json.loads(proc.stdout.readline())
                ready = json.loads(proc.stdout.readline())
                self.assertTrue(ready["ready"])
                for c in ("ja", "en", "zh", "ko", "de", "fr", "es", "pt", "it", "ru"): self.assertIn(c, ready["languages"])
                r = ask({"id": 1, "op": "read", "images": [src], "language": "en"})
                self.assertTrue(r["ok"], r); page = r["pages"][0]; self.assertEqual(len(page["blocks"]), 2); self.assertEqual(page["direction"], "ltr")
                self.assertIn("doctor", page["blocks"][1]["text"].lower()); self.assertFalse(page["blocks"][0]["vertical"])
                self.assertEqual(len(page["blocks"][0]["balloon"]["paper"]), 3); self.assertEqual(len(page["blocks"][0]["balloon"]["ink"]), 3)
                r = ask({"id": 2, "op": "read", "images": [src], "language": "en", "order": "rtl"})
                self.assertTrue(r["ok"], r); self.assertIn("doctor", r["pages"][0]["blocks"][0]["text"].lower())
                self.assertGreater(page["blocks"][0]["sure"], 0.5); self.assertEqual(len(page["blocks"][0]["crop"]), 4)
                cdir = os.path.join(tmp, "crops")
                cr = ask({"id": 9, "op": "crops", "image": src, "page": page, "out": cdir})
                self.assertTrue(cr["ok"], cr); self.assertEqual([c["id"] for c in cr["crops"]], [b["id"] for b in page["blocks"]])
                for c in cr["crops"]:
                    with Image.open(c["file"]) as im: self.assertEqual((im.format, list(im.size)), ("PNG", c["size"])); self.assertEqual(max(im.size), 1024)
                one = ask({"id": 10, "op": "crops", "image": src, "page": page, "ids": [page["blocks"][1]["id"]], "out": cdir}); self.assertEqual(len(one["crops"]), 1)
                bad = ask({"id": 11, "op": "crops", "image": src, "page": page, "ids": ["b77"], "out": cdir}); self.assertFalse(bad["ok"]); self.assertIn("b77", bad["error"]["message"])
                bad = ask({"id": 3, "op": "read", "images": [src], "language": "ar"})
                self.assertFalse(bad["ok"]); self.assertIn("'ar'", bad["error"]["message"])
                bad = ask({"id": 4, "op": "read", "images": [src], "language": "en", "order": "up"}); self.assertFalse(bad["ok"])
                s = ask({"id": 5, "op": "script", "images": [src]})
                self.assertTrue(s["ok"], s); self.assertEqual(s["script"], "latin"); self.assertFalse(s["vertical"]); self.assertGreater(s["share"], 0.9); self.assertTrue(s["texts"])
                work = os.path.join(tmp, "work")
                c = ask({"id": 6, "op": "cut", "image": ep, "out": work})
                self.assertTrue(c["ok"], c); self.assertTrue(c["strip"]); self.assertEqual((c["width"], c["height"]), strip_img.size)
                self.assertEqual(sum(p["height"] for p in c["pieces"]), strip_img.height); self.assertTrue(all(os.path.exists(p["file"]) for p in c["pieces"]))
                n = ask({"id": 7, "op": "cut", "image": src, "out": os.path.join(tmp, "none")})
                self.assertTrue(n["ok"]); self.assertFalse(n["strip"]); self.assertEqual(n["pieces"], [])
                out = os.path.join(tmp, "ep01_out.jpg")
                j = ask({"id": 8, "op": "join", "pieces": [{"file": p["file"], "top": p["top"]} for p in c["pieces"]], "width": c["width"], "height": c["height"], "out": out})
                self.assertTrue(j["ok"], j); self.assertEqual(j["out"], out)
                with Image.open(out) as im: self.assertEqual((im.format, im.size), ("JPEG", strip_img.size))
                self.assertIsNone(proc.poll())
                bye = ask({"op": "quit"}); self.assertTrue(bye["bye"]); proc.wait(timeout=30)
            finally:
                if proc.poll() is None: proc.terminate()                          # by its own process handle, never by name
                proc.stdin.close(); proc.stdout.close()

if __name__ == "__main__":
    unittest.main()
