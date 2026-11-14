"""The check a set-up ends with (comic/check.py), run as the program runs it: every piece loaded once, a line a piece
and the count last; a piece that does not load named with a reason a person can act on; a package that does not import
named by its module, and the manifest's things then not tried. And the long-lived mode's answer for such a piece: the
error type "Unloadable", which is no page's failure."""
import json, os, shutil, subprocess, sys, tempfile, unittest
import conf

MODULES = ["torch", "manga_ocr", "fugashi", "unidic_lite", "onnxruntime", "cv2", "PIL", "numpy", "yaml", "budoux"]   # ComicHelper.PYTHON_PACKAGES, by the names Python imports them by

def check(models, modules, cwd):
    """(exit code, the JSON lines of stdout) of the check, started as the program starts it: the launcher, isolated, from another folder."""
    cmd = [sys.executable, "-I", "-B", os.path.join(conf.ROOT, "comic_helper.py"), "check", "--models", models, "--threads", str(conf.THREADS)]
    for m in modules: cmd += ["--import", m]
    r = subprocess.run(cmd, cwd=cwd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, encoding="utf-8")
    lines = [l for l in r.stdout.splitlines() if l.strip()]
    return r.returncode, lines

def copy_of(models, into, broken=()):
    """The models folder again under `into`: each file a link to the one fetched (a copy where the file system links
    nothing), but for the paths in `broken`, which are copies of their own that a test may spoil."""
    from comic import models as M
    for m in M.manifest()["models"]:
        for f in m["files"]:
            src = os.path.join(models, f["path"]); dst = os.path.join(into, f["path"]); os.makedirs(os.path.dirname(dst), exist_ok=True)
            if f["path"] in broken: shutil.copyfile(src, dst); continue
            try: os.link(src, dst)
            except OSError: shutil.copyfile(src, dst)
    return into

@conf.need("COMIC_MODELS")
class EveryPieceLoadedOnce(unittest.TestCase):
    def test_every_piece_loads_and_is_a_line(self):
        from comic import models as M
        with tempfile.TemporaryDirectory() as tmp:
            rc, lines = check(conf.MODELS, MODULES, tmp)
            said = [json.loads(l) for l in lines]                                 # nothing but JSON objects on stdout
            self.assertEqual(rc, 0, said)
            last = said[-1]; pieces = said[:-1]
            things = [m["name"] for m in M.manifest()["models"]]
            self.assertEqual([p["piece"] for p in pieces], MODULES + things)      # the packages first, then every thing of the manifest, in its order
            self.assertTrue(all(p["ok"] for p in pieces), [p for p in pieces if not p["ok"]])
            self.assertEqual({p["kind"] for p in pieces[:len(MODULES)]}, {"package"})
            self.assertEqual([p["kind"] for p in pieces[len(MODULES):]], [m.get("kind", "model") for m in M.manifest()["models"]])
            self.assertEqual((last["checked"], last["failed"]), (len(MODULES) + len(things), 0))
            self.assertIsInstance(last["sec"], float)

    def test_a_piece_that_does_not_load_is_named_with_its_reason(self):
        cut, gone, emptied = "ppocr/korean/inference.onnx", "fonts/NotoSansCJKkr-Medium.otf", "hyphenation/hyph-de.pat.txt"
        with tempfile.TemporaryDirectory() as tmp:
            models = copy_of(conf.MODELS, os.path.join(tmp, "models"), broken=(cut, emptied))
            with open(os.path.join(models, cut), "r+b") as f: f.truncate(5_000_000)          # a download that stopped halfway
            os.remove(os.path.join(models, gone))
            with open(os.path.join(models, emptied), "w"): pass
            rc, lines = check(models, ["yaml", "budoux"], tmp)
            said = [json.loads(l) for l in lines]; bad = {p["piece"]: p for p in said[:-1] if not p["ok"]}
            self.assertEqual(rc, 1)
            self.assertEqual(sorted(bad), ["font-korean", "hyphenation-de", "reader-korean"])
            self.assertEqual(bad["reader-korean"]["why"], "its file ppocr/korean/inference.onnx is not as it was fetched")
            self.assertEqual(bad["font-korean"]["why"], "its file fonts/NotoSansCJKkr-Medium.otf is missing")
            self.assertEqual(bad["hyphenation-de"]["why"], "its file hyphenation/hyph-de.pat.txt is not as it was fetched")
            self.assertTrue(all("module" not in p for p in bad.values()))                     # a file's failure names no package
            self.assertEqual(said[-1]["failed"], 3)
            ok = {p["piece"] for p in said[:-1] if p["ok"]}
            self.assertTrue({"detector", "reader", "reader-latin", "reader-chinese", "font-japanese", "hyphenation-en-us", "words-chinese"} <= ok, ok)   # the others are all tried

    def test_a_package_that_does_not_import_is_named_and_the_rest_is_not_tried(self):
        with tempfile.TemporaryDirectory() as tmp:
            rc, lines = check(conf.MODELS, ["yaml", "no_such_module_of_this_test", "budoux"], tmp)
            said = [json.loads(l) for l in lines]
            self.assertEqual(rc, 1)
            self.assertEqual([(p["piece"], p["ok"]) for p in said[:-1]], [("yaml", True), ("no_such_module_of_this_test", False), ("budoux", True)])
            self.assertEqual(said[1]["why"], "No module named 'no_such_module_of_this_test'"); self.assertEqual(said[1]["module"], "no_such_module_of_this_test")
            self.assertEqual((said[-1]["checked"], said[-1]["failed"]), (3, 1))               # no thing of the manifest tried: it would fail for the package's reason

class WhatIsSaidOfAPieceThatDoesNotLoad(unittest.TestCase):
    def test_a_file_is_the_pieces_and_an_import_is_the_packages(self):
        from comic import models as M
        e = M.Unloadable("the model that reads Korean lettering", "its file ppocr/korean/inference.onnx is missing")
        self.assertEqual(str(e), "the model that reads Korean lettering does not load (its file ppocr/korean/inference.onnx is missing)"); self.assertIsNone(e.module)
        # the look at a comic's writing loads every line reader: with PyYAML gone the first to fail was the Chinese one, whatever the comic
        e = M.Unloadable("the model that reads Chinese lettering", ModuleNotFoundError("No module named 'yaml'", name="yaml"))
        self.assertEqual(str(e), "a Python package the helper needs does not load (No module named 'yaml')"); self.assertEqual(e.module, "yaml")
        e = M.Unloadable("the model that reads Japanese lettering", OSError("[WinError 126] The specified module could not be found.\nError loading c10.dll"))
        self.assertEqual(str(e), "the model that reads Japanese lettering does not load ([WinError 126] The specified module could not be found. Error loading c10.dll)")

@conf.need("COMIC_MODELS", "COMIC_CJK_FONT")
class AReaderThatDoesNotLoad(unittest.TestCase):
    def test_serve_answers_it_as_the_helpers_own_failure(self):
        import pages
        cut = "ppocr/korean/inference.onnx"
        with tempfile.TemporaryDirectory() as tmp:
            models = copy_of(conf.MODELS, os.path.join(tmp, "models"), broken=(cut,))
            with open(os.path.join(models, cut), "r+b") as f: f.truncate(5_000_000)
            src = os.path.join(tmp, "page.png"); pages.page(conf.CJK, conf.CJK_INDEX).save(src)
            proc = subprocess.Popen([sys.executable, "-I", "-B", os.path.join(conf.ROOT, "comic_helper.py"), "serve", "--models", models, "--threads", str(conf.THREADS)],
                                    cwd=tmp, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, encoding="utf-8")
            try:
                def ask(req):
                    proc.stdin.write(json.dumps(req) + "\n"); proc.stdin.flush(); return json.loads(proc.stdout.readline())
                self.assertTrue(json.loads(proc.stdout.readline())["ready"])                  # the page detector loads: the start says nothing of a reader
                r = ask({"id": 1, "op": "read", "images": [src], "language": "ko"})
                self.assertFalse(r["ok"]); self.assertEqual(r["error"]["type"], "Unloadable")
                self.assertEqual(r["error"]["message"], "the model that reads Korean lettering does not load (its file ppocr/korean/inference.onnx is not as it was fetched)")
                r = ask({"id": 2, "op": "script", "images": [src]})                           # the look loads every reader: the same failure
                self.assertFalse(r["ok"]); self.assertEqual(r["error"]["type"], "Unloadable")
                r = ask({"id": 3, "op": "read", "images": [src], "language": "xx"})           # a language no reader knows is the caller's error, as ever
                self.assertFalse(r["ok"]); self.assertEqual(r["error"]["type"], "ValueError")
                r = ask({"id": 4, "op": "read", "images": [os.path.join(tmp, "no-such-file.png")], "language": "en"})   # and a page's failure is a page's
                self.assertFalse(r["ok"]); self.assertNotEqual(r["error"]["type"], "Unloadable")
                r = ask({"id": 5, "op": "read", "images": [src], "language": "ja"})           # a reader that loads still reads
                self.assertTrue(r["ok"], r); self.assertEqual(len(r["pages"][0]["blocks"]), 4)
                self.assertIsNone(proc.poll())                                                # alive after all of them
                self.assertTrue(ask({"op": "quit"})["bye"]); proc.wait(timeout=30)
            finally:
                if proc.poll() is None: proc.terminate(); proc.wait(timeout=30)               # by its own process handle, never by name
                proc.stdin.close(); proc.stdout.close()

if __name__ == "__main__":
    unittest.main()
