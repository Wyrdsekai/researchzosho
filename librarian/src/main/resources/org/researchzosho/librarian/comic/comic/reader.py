"""The text readers, one per writing system behind one call: reader(crop) -> the block's text.

Japanese                  Reader: manga-ocr, which takes a whole block (columns or lines) at once. Loaded from a local
                          folder (the manifest's files), on the CPU.
Every other language      BlockReader: the block is cut into its lines (lines.Finder) and each line is read by a
                          LineReader, PP-OCRv5's text-line reader for the writing system (ONNX Runtime on the CPU):
                            latin     Latin-alphabet languages (the model's letters cover Western, Central and
                                      Northern European alphabets, Turkish, Romanian; not Vietnamese)
                            cyrillic  Russian, Ukrainian, Belarusian, Bulgarian, Serbian, Macedonian and the others
                            greek     Greek
                                      (in both, Latin letters read for their twins of the same shape are put
                                      right: own_letters)
                            korean    hangul (and Latin letters)
                            chinese   simplified and traditional characters
                          Lines are joined by a space (by nothing for Chinese). A block lettered in columns (Chinese in
                          the traditional layout) is read column by column, right to left, each column turned a quarter
                          turn as the model was trained to take it.
No network call: the hub is switched to offline before manga-ocr loads, and every weight is read from the folder given.

LineReader's input, as PaddleOCR feeds the model: one line, BGR, 48 px high with its shape kept (3200 px wide at most),
(x / 255 - 0.5) / 0.5, lines of a batch padded on the right to the widest. Output: per step a distribution over
[blank] + the model's own character list (inference.yml: PostProcess.character_dict) + [space]; the text is the
greedy CTC decoding, the confidence the mean probability of its characters."""
import os
import re
import numpy as np
from . import lines as L

LATIN = ("en de fr es pt it nl sv da no nb nn fi is pl cs sk sl hr bs hu ro tr id ms tl ca gl eu la et lv lt sq af sw ga cy mt eo oc "
         "lb rm az uz ku mi qu").split()
CYRILLIC = "ru uk be bg sr mk kk ky tg mn tt ba cv".split()
SYSTEMS = {"ja": "japanese", "zh": "chinese", "ko": "korean", "el": "greek"}
SYSTEMS.update({c: "latin" for c in LATIN}); SYSTEMS.update({c: "cyrillic" for c in CYRILLIC})
JOIN = {"chinese": "", "japanese": ""}

TWINS = {"cyrillic": str.maketrans("AaBCcEeHKkMOoPpTXxy", "АаВСсЕеНКкМОоРрТХху"), "greek": str.maketrans("ABEHIKMNOPTXYZov", "ΑΒΕΗΙΚΜΝΟΡΤΧΥΖον")}

SAME_SHAPE = set("COSUVWXZ")          # Latin letters whose capital is the small letter made larger

def own_letters(text, system):
    """A line as its alphabet writes it, where the line reader is known to slip.
    Cyrillic and Greek: the reader knows Latin letters too and gives some for the letters of the same shape (HO for
    НО, Xлоп for Хлоп): a word whose Latin letters all have such a twin is written with the twins.
    Capitals inside a word that begins small: Cyrillic small letters are small capitals in shape, and the reader
    gives some as capitals (своИ), so such a word is written small throughout; in the Latin alphabet the same holds
    for the letters C O S U V W X Z only (vOUs is vous; iPhone is left alone)."""
    twins = TWINS.get(system)
    if not twins and system != "latin": return text
    def word(m):
        w = m.group()
        if twins:
            latin = [c for c in w if c.isascii()]
            if latin and all(ord(c) in twins for c in latin): w = w.translate(twins)
        if len(w) > 1 and w[0].islower() and not w.islower():
            if system == "cyrillic" or (system == "latin" and all(c in SAME_SHAPE for c in w[1:] if c.isupper())): w = w.lower()
        return w
    return re.sub(r"[^\W\d_]+", word, text)

def system_of(language):
    """The writing system of a language code ("ko", "pt-BR", "sr-Latn", "zh_TW"); absent means Japanese. A language no
    reader knows raises ValueError naming it."""
    tags = (language or "ja").lower().replace("_", "-").split("-")
    if "latn" in tags[1:]: return "latin"
    if "cyrl" in tags[1:]: return "cyrillic"
    if tags[0] not in SYSTEMS:
        raise ValueError(f"no reader for the language {language!r}: the helper reads " + " ".join(languages()))
    return SYSTEMS[tags[0]]

def languages():
    """The language codes the helper can read."""
    return ["ja", "zh", "ko"] + LATIN + CYRILLIC + ["el"]

class Reader:
    def __init__(self, model_dir, threads=4):
        os.environ.setdefault("HF_HUB_OFFLINE", "1"); os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")
        import torch
        torch.set_num_threads(int(threads))
        from manga_ocr import MangaOcr
        import logging
        try:
            from loguru import logger
            logger.remove()                                   # the library logs to stderr on load; a caller reads stdout as data
        except Exception: pass
        self.mocr = MangaOcr(pretrained_model_name_or_path=model_dir, force_cpu=True)

    def __call__(self, image):
        return self.mocr(image.convert("RGB"))

class LineReader:
    def __init__(self, model_dir, threads=4):
        import onnxruntime as ort
        import yaml
        so = ort.SessionOptions(); so.intra_op_num_threads = int(threads); so.inter_op_num_threads = 1; so.log_severity_level = 3
        self.sess = ort.InferenceSession(os.path.join(model_dir, "inference.onnx"), sess_options=so, providers=["CPUExecutionProvider"])
        with open(os.path.join(model_dir, "inference.yml"), encoding="utf-8") as f:
            self.chars = [""] + [str(c) for c in yaml.safe_load(f)["PostProcess"]["character_dict"]] + [" "]
        self.inp = self.sess.get_inputs()[0].name

    def __call__(self, images, batch=8):
        """[(text, confidence)] for line images (PIL), in the order given."""
        import cv2
        xs = []
        for im in images:
            a = L.bgr(im); h, w = a.shape[:2]; rw = int(min(3200, max(8, np.ceil(48.0 * w / max(1, h)))))
            xs.append(((cv2.resize(a, (rw, 48), interpolation=cv2.INTER_LINEAR).astype(np.float32) / 255.0 - 0.5) / 0.5).transpose(2, 0, 1))
        out = [None] * len(xs); order = sorted(range(len(xs)), key=lambda i: xs[i].shape[2])
        for k in range(0, len(order), batch):
            idx = order[k:k + batch]; b = np.zeros((len(idx), 3, 48, max(xs[i].shape[2] for i in idx)), dtype=np.float32)
            for j, i in enumerate(idx): b[j, :, :, :xs[i].shape[2]] = xs[i]
            p = self.sess.run(None, {self.inp: b})[0]
            for j, i in enumerate(idx):
                ids = p[j].argmax(axis=1); pr = p[j].max(axis=1); s, cs, prev = [], [], 0
                for t, c in enumerate(ids):
                    if c != 0 and c != prev and c < len(self.chars): s.append(self.chars[c]); cs.append(float(pr[t]))
                    prev = c
                out[i] = ("".join(s), float(np.mean(cs)) if cs else 0.0)
        return out

class BlockReader:
    """A block's text from its crop, for one writing system: the finder cuts, the line reader reads."""
    def __init__(self, finder, line_reader, system):
        self.finder, self.line_reader, self.system = finder, line_reader, system

    def block(self, crop):
        """{"text", "vertical", "lines": [{"box" (in the crop's pixels), "thick" (the line's height, a column's width),
        "text", "confidence"}], "sure"}; the lines in reading order (columns right to left when vertical). sure: the
        lowest confidence among the lines, None when no line was read. Measured on eleven Pepper&Carrot editions
        (1,383 blocks, 330 with a wrong letter): under 0.8 fall 53 % of the wrong blocks and 9.5 % of the right ones;
        under 0.9, 68 % and 18 %. The lowest line tells more than the mean over the lines."""
        crop = crop.convert("RGB"); boxes = self.finder(crop)
        cols = L.columns(boxes) if self.system in ("chinese", "korean") else []
        if cols: order, cuts = cols, [L.turned(crop, b) for b in cols]
        else:
            order = L.rows(boxes); cuts = [L.cut(crop, b) for b in order]
        keep = [(b, c) for b, c in zip(order, cuts) if c.width >= 2 and c.height >= 2]
        got = self.line_reader([c for _, c in keep]) if keep else []
        lines = [{"box": [round(float(v), 1) for v in b[:4]], "thick": round(L.thick(b), 1), "text": own_letters(" ".join(t.split()), self.system), "confidence": round(cf, 3)} for (b, _), (t, cf) in zip(keep, got)]
        lines = [l for l in lines if l["text"]]
        sep = JOIN.get(self.system, " ")
        return {"text": sep.join(l["text"] for l in lines), "vertical": bool(cols), "lines": lines, "sure": min((l["confidence"] for l in lines), default=None)}

    def __call__(self, crop):
        return self.block(crop)["text"]
