"""Speech or a drawn sound word, decided per block from what a program can see. The Japanese thresholds were set on 29
pages of one manga; the rules for the other writing systems were set by eye on the Pepper&Carrot editions and on
public-domain American pages. They are the open part of this package.
  joined   share of the dark pixels in the text box that the balloon's paper does not enclose (closed balloons only)
  stroke   stroke width (twice the 90th percentile of the ink's distance transform) over the page's dialogue strokes
  size     glyph size over the page's dialogue glyph size
  shape    a sound pattern, by writing system, punctuation and long marks apart:
             japanese   kana only, at most 6 letters, and short (3 or fewer), or repeats a kana, or ends in a small tsu
             chinese    han or kana only, at most 4 letters, with a letter said twice running
             korean     hangul only, at most 4 letters, with a letter said twice running
                        (one large letter alone is not taken for a sound: on the Korean and Chinese pages looked
                        at it was a shout, 你…, 哦, 오, more often than a noise)
             alphabets  one word of at most 12 letters with a letter three times running (AAAH, BZZZT) or without
                        any vowel (PFFT, GRRR)
The page's dialogue (the medians strokes and sizes are measured against) = its blocks in balloons that are plainly
sentences: Japanese 6 letters or more with a kanji; Chinese and Korean 5 letters or more; alphabets 12 letters or more
in two words or more.
In Japanese, lettering whose strokes run into the balloon's outline is drawn, not set (rule 1). In the other writing
systems every rule asks for a sound pattern: a block is a sound word when it is one and is drawn (joined, heavy, large,
or outside a balloon). Rule 5 is Japanese only (katakana is the script of sound words)."""
import re
import unicodedata
import cv2
import numpy as np

KANA = re.compile(r"[ぁ-ゖァ-ヺ]+"); KATA = re.compile(r"[ァ-ヺ]+")
HAN = re.compile(r"[ぁ-ゖァ-ヺ㐀-䶿一-鿿]+"); HANGUL = re.compile(r"[가-힣ㄱ-ㆎ]+")
VOWELS = set("aeiouyàáâãäåæèéêëìíîïòóôõöøœùúûüýÿāăąēėęěīįōőūůűаеёиоуыэюяіїєαεηιουωάέήίόύώ")
_DASH = set("ー―—–‐‑‒─━〜~～-ｰ_")
def letters(s):
    out = []
    for c in unicodedata.normalize("NFKC", s or ""):
        if c.isspace() or c in _DASH or unicodedata.category(c)[0] in ("P", "S"): continue
        out.append(c)
    return "".join(out)

def shape_of(text, system="japanese"):
    t = letters(text)
    if not t: return False
    if system == "japanese":
        if not KANA.fullmatch(t) or len(t) > 6: return False
        return len(t) <= 3 or bool(re.search(r"(.)\1", t)) or bool(re.search(r"(..)\1", t)) or t[-1] in "っッ"
    if system in ("chinese", "korean"):
        if not (HAN if system == "chinese" else HANGUL).fullmatch(t) or len(t) > 4: return False
        return bool(re.search(r"(.)\1", t))
    if len(t) > 12 or len((text or "").split()) != 1 or not t.isalpha(): return False
    low = t.lower()
    return bool(re.search(r"(.)\1\1", low)) or not any(c in VOWELS for c in low)

def dialogue(text, system="japanese"):
    """Whether a block's text is plainly a sentence of dialogue (the page's measure of ordinary lettering)."""
    t = letters(text)
    if system == "japanese": return len(t) >= 6 and bool(re.search(r"[一-鿿]", text))
    if system in ("chinese", "korean"): return len(t) >= 5
    return len(t) >= 12 and len((text or "").split()) >= 2

def strokes(ink):
    """(stroke width, glyph size) of an ink mask, or (None, None)."""
    if ink.sum() < 30: return None, None
    dt = cv2.distanceTransform(ink.astype(np.uint8), cv2.DIST_L2, 3)
    n, lab, st, _ = cv2.connectedComponentsWithStats(ink.astype(np.uint8), connectivity=8)
    big = np.maximum(st[1:, 2], st[1:, 3]); big = big[st[1:, 4] >= 6]
    return 2.0 * float(np.percentile(dt[ink], 90)), (float(np.percentile(big, 85)) if len(big) else None)

def decide(in_balloon, text, joined, stroke, size, system="japanese"):
    """(is a sound word, why). In order:
    1. at least 0.4 of the lettering joins the outline (in every writing system but Japanese it must be a sound
       pattern as well: on American pages of the 1940s hand lettering crowds the outline, and 13 of 246 blocks of
       plain speech joined it);
    2. a sound pattern in strokes at least 1.5 times the page's dialogue strokes;
    3. a sound pattern in glyphs at least 1.6 times the dialogue glyphs, the strokes no lighter than dialogue;
    4. a sound pattern outside any balloon;
    5. Japanese only: katakana only, at most 6 letters, with a weaker sign of drawn lettering (0.1 joined, strokes
       1.3 times, or glyphs 1.15 times)."""
    sh = shape_of(text, system)
    if joined is not None and joined >= 0.4 and (sh or system == "japanese"): return True, "lettering joins the outline"
    if sh and stroke is not None and stroke >= 1.5: return True, "a sound pattern in strokes much heavier than the page's dialogue"
    if sh and size is not None and size >= 1.6 and (stroke is None or stroke >= 1.0): return True, "a sound pattern in glyphs much larger than the page's dialogue"
    if sh and not in_balloon: return True, "a sound pattern outside any balloon"
    t = letters(text)
    if system == "japanese" and t and KATA.fullmatch(t) and len(t) <= 6 and ((joined or 0) >= 0.1 or (stroke or 0) >= 1.3 or (size or 0) >= 1.15):
        return True, "short katakana with a sign of drawn lettering"
    return False, None
