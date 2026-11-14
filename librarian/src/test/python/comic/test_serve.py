"""The long-lived mode, started as the program starts it: the ready line, a read, three bad requests, a set, a picture,
and the process still alive after them."""
import json, os, subprocess, sys, tempfile, unittest
import conf, pages

@conf.need("COMIC_MODELS", "COMIC_CJK_FONT", "COMIC_FONT")
class JsonLines(unittest.TestCase):
    def test_serve(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = os.path.join(tmp, "page.png"); pages.page(conf.CJK, conf.CJK_INDEX).save(src)
            # as the program starts it: the launcher, in Python's isolated mode, from another folder
            proc = subprocess.Popen([sys.executable, "-I", "-B", os.path.join(conf.ROOT, "comic_helper.py"), "serve", "--models", conf.MODELS, "--threads", str(conf.THREADS),
                                     "--font", conf.FONT, "--hyphenation", os.path.join(conf.MODELS, "hyphenation")],
                                    cwd=tmp, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, encoding="utf-8")
            try:
                def ask(line):
                    proc.stdin.write(line + "\n"); proc.stdin.flush(); return json.loads(proc.stdout.readline())
                ready = json.loads(proc.stdout.readline())
                self.assertTrue(ready["ready"]); self.assertIn("detector", ready["models"]); self.assertIn("reader", ready["models"]); self.assertTrue(ready["models"]["manga-ocr"])
                r1 = ask(json.dumps({"id": 1, "op": "read", "images": [src]}))
                self.assertTrue(r1["ok"]); self.assertEqual(r1["id"], 1); self.assertEqual(len(r1["pages"][0]["blocks"]), 4)
                bad = ask("this is not json"); self.assertFalse(bad["ok"]); self.assertIn("message", bad["error"])
                bad2 = ask(json.dumps({"id": 7, "op": "dance"})); self.assertFalse(bad2["ok"]); self.assertEqual(bad2["id"], 7)
                bad3 = ask(json.dumps({"id": 8, "op": "read", "images": [os.path.join(tmp, "no-such-file.png")]})); self.assertFalse(bad3["ok"])
                page = r1["pages"][0]; out = os.path.join(tmp, "set.jpg")
                texts = {b["id"]: {"text": "Good morning, everyone.", "lang": "en"} for b in page["blocks"]}
                r2 = ask(json.dumps({"id": 2, "op": "set", "image": src, "page": page, "texts": texts, "out": out}))
                self.assertTrue(r2["ok"], r2); self.assertTrue(os.path.exists(out)); self.assertEqual(sum(1 for v in r2["report"]["blocks"].values() if v["set"]), 3)
                pic = os.path.join(tmp, "boxes.jpg")
                r3 = ask(json.dumps({"id": 3, "op": "picture", "image": src, "boxes": [[str(n), b["box"]] for n, b in enumerate(page["blocks"], 1)], "out": pic}))
                self.assertTrue(r3["ok"], r3); self.assertEqual(max(r3["size"]), 1300); self.assertTrue(os.path.getsize(pic) > 0)
                self.assertIsNone(proc.poll())                                    # still alive after three bad requests
                bye = ask(json.dumps({"op": "quit"})); self.assertTrue(bye["bye"]); proc.wait(timeout=30)
            finally:
                if proc.poll() is None: proc.terminate()                          # by its own process handle, never by name
                proc.stdin.close(); proc.stdout.close()

if __name__ == "__main__":
    unittest.main()
