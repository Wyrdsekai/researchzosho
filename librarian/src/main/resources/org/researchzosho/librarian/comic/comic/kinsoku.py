"""Where a line of Chinese, Japanese or Korean may end. Pure functions on strings: no font and no page.

Chinese and Japanese are written without spaces, so a line may end between any two characters except where the rules
of kinsoku forbid it:
  - never before a closing bracket, a comma, a full stop, the middle dot, a colon, ！ or ？, a small kana, the prolonged
    sound mark ー, an iteration mark (々 ゝ ヽ), a wave dash or a percent sign;
  - never after an opening bracket, or a currency sign;
  - never inside a run of Latin letters or digits (DNA, 1000, 3.5), inside …… or ——, or inside a run of ！ and ？.
What lies between two places where a line may end is a unit; a line is made of whole units, so kinsoku is never broken.
A break between two units is free where a phrase ends: after a comma, a full stop, ！ ？ … or ——, before an opening
bracket, at a space, and where a phrase boundary is known. Two sources say where phrases end:
  - for Japanese, the dictionary the Japanese reader already brings (fugashi with a UniDic): a phrase begins at each
    word that can stand alone (a noun, a verb, an adjective, an adverb ...) and takes the particles and endings after it;
  - BudouX (Google, Apache 2.0; the budoux package, with models for Japanese and for simplified and traditional
    Chinese): used for Chinese, and for Japanese where the dictionary is not installed. Measured against two
    letterers' own line ends the dictionary marks more of them than BudouX does (see the notes).
BudouX ends some Chinese phrases in the middle of a word (交|通事故, 医|生). So for Chinese a list of words (the
manifest's `words-chinese`, read by Words) says where words end: a phrase ends only where BudouX says so and a word
ends; the end of any other word is cheap ('soft'); a place inside a word is the dearest ('mid'). Without BudouX every
word's end is free; without the list BudouX's phrase ends stand as they are.
Inside a phrase a break is cheap where a word begins ('soft': the second noun of a compound, a verb that leans on the
particle before it as いる does in している; without the dictionary, where the writing shows it: a kanji or a katakana
word after hiragana, a kanji after katakana). Anywhere else inside a phrase a break is dear: before a particle or an
ending ('in'), and dearer still in the middle of a word the dictionary knows ('mid'). With neither source installed
the lines still obey kinsoku and end at punctuation, at the word beginnings the writing shows, or anywhere at the
dear price (phrases_known() says whether a source is there).

Some places are dear whatever the sources say: between a number and the character after it (30 分, 25 歳); in
Chinese before a particle that leans on the word before it (的 了 吗 呢 吧 啊 们); before …… that trails a word; and
inside brackets or quotation marks that hold eight characters or fewer (“自由诊疗”).

Korean breaks at its spaces. A word of four syllables or more may also break between two syllables, two at least on
each side (its punctuation not counted), with no hyphen; the search treats that as it treats a hyphenated break in an
alphabetic language ('syl').

atoms() gives the list setter.layout() takes: [(piece, kind)], the kind saying what may happen after the piece:
  'word'  a space follows; a break here is free and the space is not drawn
  'wide'  the same, the space being the full-width one (U+3000), which is kept as wide as it was written
  'nat'   a break here is free and nothing is added (a phrase ends)
  'soft'  a break here costs a little and nothing is added (inside a phrase, where a word begins)
  'in'    a break here costs more and nothing is added (inside a phrase: before a particle or an ending, or where
          no source says what the place is)
  'mid'   a break here costs most and nothing is added (in the middle of a word the dictionary knows)
  'syl'   a break inside a Korean word; it costs what a hyphen costs and nothing is added"""
import math
import os
import re
import unicodedata

OPEN = "（〔［｛〈《「『【〘〖〝‘“｟«([{"
CLOSE = "）〕］｝〉》」』】〙〗〟’”｠»)]}"
STOPS = "、。，．,.・･·：；:;"
BANGS = "！？!?‼⁇⁈⁉"
SMALL = "ぁぃぅぇぉっゃゅょゎゕゖァィゥェォッャュョヮヵヶㇰㇱㇲㇳㇴㇵㇶㇷㇸㇹㇺㇻㇼㇽㇾㇿｧｨｩｪｫｬｭｮｯ"
LONG = "ーｰ"
ITERATION = "々〻ゝゞヽヾ"
DOTS = "…‥"
DASHES = "—―─"
NO_START = frozenset(CLOSE + STOPS + BANGS + SMALL + LONG + ITERATION + "〜～゛゜％‰°′″℃%‐–-")
NO_END = frozenset(OPEN + "＄￥￡$¥£＃#№")
FREE_AFTER = frozenset("、。，．,.：；:;" + BANGS + DOTS + DASHES)               # a phrase ends after these
JOINERS = ".,'’-/:&+@_"                                                         # inside a run of letters and digits: 3.5, 1,000, e-mail

def is_cjk(lang):
    """Whether a language code names Chinese, Japanese or Korean: the languages set by these rules."""
    return (lang or "").lower().replace("_", "-").split("-")[0] in ("ja", "zh", "ko")

def region(lang):
    """Whose conventions a language code asks for: 'jp', 'sc' (simplified Chinese), 'tc' (traditional Chinese), 'kr', or None."""
    parts = (lang or "").lower().replace("_", "-").split("-")
    if parts[0] == "ja": return "jp"
    if parts[0] == "ko": return "kr"
    if parts[0] == "zh": return "tc" if any(p in ("hant", "tw", "hk", "mo") for p in parts[1:]) else "sc"
    return None

def alnum(c):
    """A letter or a digit of an alphabet (Latin, Greek, Cyrillic, and their full-width forms): not a CJK character."""
    if "\uff10" <= c <= "\uff19" or "\uff21" <= c <= "\uff3a" or "\uff41" <= c <= "\uff5a": return True
    if "\uff61" <= c <= "\uff9f": return False                         # half-width katakana are kana, not letters of an alphabet
    return c.isalnum() and unicodedata.east_asian_width(c) not in ("W", "F")

def clusters(token):
    """A string without spaces as the pieces no rule ever looks inside: a run of letters and digits with the marks
    inside it, or one character with the combining marks that follow it."""
    out, i, n = [], 0, len(token)
    while i < n:
        j = i + 1
        if alnum(token[i]):
            while j < n and (alnum(token[j]) or unicodedata.combining(token[j]) or (token[j] in JOINERS and j + 1 < n and alnum(token[j + 1]))): j += 1
        else:
            while j < n and (unicodedata.combining(token[j]) or token[j] in "\u200d\ufe0e\ufe0f" or "\U000e0100" <= token[j] <= "\U000e01ef"): j += 1
        out.append(token[i:j]); i = j
    return out

def units(token):
    """A string without spaces as its units: the pieces a line is made of. A line may end after any unit and nowhere else."""
    out = []
    for c in clusters(token):
        if out:
            a, b = out[-1][-1], c[0]
            if b in NO_START or a in NO_END or (a in DOTS and b in DOTS) or (a in DASHES and b in DASHES):
                out[-1] += c; continue
        out.append(c)
    return out

_parsers = {}
def _parser(lang):
    """The BudouX parser for a language code; None for a language it has no model for, and where the package is not installed."""
    r = region(lang); kind = {"jp": "ja", "sc": "zh-hans", "tc": "zh-hant"}.get(r)
    if kind is None: return None
    if kind not in _parsers:
        try:
            import budoux
            _parsers[kind] = {"ja": budoux.load_default_japanese_parser, "zh-hans": budoux.load_default_simplified_chinese_parser,
                              "zh-hant": budoux.load_default_traditional_chinese_parser}[kind]()
        except Exception: _parsers[kind] = None
    return _parsers[kind]

CONTENT = frozenset(("名詞", "代名詞", "形状詞", "連体詞", "副詞", "接続詞", "感動詞", "動詞", "形容詞", "接頭辞"))     # the dictionary's classes of words that can stand alone
_tagger = False
def _words_ja(token):
    """A Japanese string as the dictionary's words: [(surface, class, subclass, sub-subclass, dictionary form)]; None
    without the dictionary, and when its words do not add up to the string."""
    global _tagger
    if _tagger is False:
        try:
            import fugashi
            _tagger = fugashi.Tagger()
        except Exception: _tagger = None
    if _tagger is None: return None
    try: out = [(w.surface, w.feature.pos1 or "", w.feature.pos2 or "", w.feature.pos3 or "", getattr(w.feature, "lemma", None) or "") for w in _tagger(token)]
    except Exception: return None
    return out if "".join(w[0] for w in out) == token else None

def phrases_by_dictionary(token):
    """(the offsets where a phrase ends, the offsets where a word begins inside a phrase, the offsets where any of the
    dictionary's words ends) of a Japanese string without spaces; (None, None, None) without the dictionary.
    A phrase begins at a word that can stand alone, except: after a prefix; a counter after its number; a verb or an
    adjective that leans on the stem before it (若 すぎる, 食べ 始める); する after its noun (合格 する); ない after
    a particle or an ending (では ない, 若く ない); そう, よう and みたい after what they qualify (難し そう). Inside a phrase a word begins, cheaply, at the second noun of a
    compound (大学 病院) and at a leaning verb after a particle (して いる, 研修を する, と いう)."""
    words = _words_ja(token)
    if words is None: return None, None, None
    ends, begins, edges, at, prev = set(), set(), set(), 0, None
    for surface, pos, pos2, pos3, lemma in words:
        edges.add(at)
        if prev is not None and pos in CONTENT:
            ps, ppos, ppos2 = prev; leans = pos2 == "非自立可能" and pos in ("動詞", "形容詞")
            if ppos == "接頭辞" or (ppos2 == "数詞" and (pos2 == "数詞" or "助数詞" in pos3)): pass
            elif pos2 == "助動詞語幹": pass                                   # そう, よう, みたい after what they qualify (難し そう, する よう)
            elif leans and ppos in ("動詞", "形容詞", "助動詞", "名詞"): pass
            elif leans and lemma == "無い": pass
            elif leans and ppos == "助詞": begins.add(at)
            elif pos == "名詞" and ppos == "名詞": begins.add(at)
            else: ends.add(at)
        at += len(surface); prev = (surface, pos, pos2)
    return ends, begins, edges

class Words:
    """Chinese words from a list of words with their counts (the manifest's `words-chinese`: one "word count ..." a
    line, simplified and traditional). A text is cut into the words that make it most probable, each word weighed by
    its count; a stretch the list does not know falls into single characters."""
    def __init__(self, path):
        self.count = {}; total = 0
        with open(path, encoding="utf-8") as f:
            for line in f:
                part = line.split(" ")
                if len(part) < 2 or not part[1].strip().isdigit(): continue
                w, n = part[0], int(part[1]); self.count[w] = n; total += n
                for i in range(1, len(w)): self.count.setdefault(w[:i], 0)          # a beginning of a word: the search goes on from it
        self.total = math.log(max(2, total))

    def ends(self, text):
        """The offsets in a text where a word ends."""
        n = len(text); best = [(0.0, n)] * (n + 1)
        for i in range(n - 1, -1, -1):
            got = None; j = i + 1
            while True:
                c = self.count.get(text[i:j])
                if c is None and j > i + 1: break
                if c or j == i + 1:
                    cand = (math.log(c or 1) - self.total + best[j][0], j)
                    if got is None or cand > got: got = cand
                if c is None or j >= n: break
                j += 1
            best[i] = got
        out, i = set(), 0
        while i < n: i = best[i][1]; out.add(i)
        return out

_words = {"folder": None, "loaded": False, "words": None}
def words_in(folder):
    """Names the folder the Chinese word list lies in (the manifest's words/ folder); it is read at its first use."""
    if folder != _words["folder"]: _words.update(folder=folder, loaded=False, words=None)

def chinese_words():
    """The Chinese word list, or None where there is none."""
    if not _words["loaded"]:
        _words["loaded"] = True; path = os.path.join(_words["folder"] or "", "chinese.txt")
        try: _words["words"] = Words(path) if _words["folder"] and os.path.exists(path) else None
        except Exception: _words["words"] = None
    return _words["words"]

def phrases_known(lang):
    """Whether phrase boundaries are known for the language: True for Korean (its spaces); for Japanese with the
    dictionary or budoux installed; for Chinese with budoux installed."""
    r = region(lang)
    return r == "kr" or _parser(lang) is not None or (r == "jp" and _words_ja("日本語") is not None) or (r in ("sc", "tc") and chinese_words() is not None)

def boundaries(token, lang):
    """The offsets into a string without spaces where a phrase ends, as BudouX has them; an empty set without a parser."""
    p = _parser(lang)
    if p is None: return set()
    out, n = set(), 0
    for chunk in p.parse(token):
        n += len(chunk); out.add(n)
    return out

def kind_of(c):
    """'han', 'hira', 'kata', 'hangul' or None for a character."""
    if "\u4e00" <= c <= "\u9fff" or "\u3400" <= c <= "\u4dbf" or "\uf900" <= c <= "\ufaff" or c in "々〆" or c >= "\U00020000": return "han"
    if "\u3041" <= c <= "\u3096" or c in "ゝゞ": return "hira"
    if "\u30a1" <= c <= "\u30fa" or c in "ーヽヾ": return "kata"
    if "\uac00" <= c <= "\ud7a3": return "hangul"
    return None

ENCLITICS = frozenset("的了吗呢吧啊呀嘛啦们嗎們么麼")      # Chinese particles that lean on the word before them: a line does not begin with one for free
WORD_BEGINS = {("hira", "han"), ("hira", "kata"), ("kata", "han"), ("han", "kata")}

def atoms(text, lang):
    """The text as the pieces a line is made of, each with what may happen after it (see the top of this file)."""
    out = []; korean = region(lang) == "kr"; parts = re.split(r"(\s+)", text.strip())
    for n in range(0, len(parts), 2):
        token = parts[n]; last = "wide" if n + 1 < len(parts) and "\u3000" in parts[n + 1] else "word"       # the space after it, as it was written
        if not token: continue
        us = units(token)
        if korean:                                            # a break only with two letters before it and two after it in the word
            letters = [any(kind_of(c) is not None or alnum(c) for c in u) for u in us]; pieces = [""]
            for i, u in enumerate(us):
                pieces[-1] += u
                if i < len(us) - 1 and sum(letters[:i + 1]) >= 2 and sum(letters[i + 1:]) >= 2 and letters[i + 1]: pieces.append("")
            out += [(u, "syl") for u in pieces[:-1]] + [(pieces[-1], last)]
            continue
        at = 0; chinese = region(lang) in ("sc", "tc"); ends, begins, edges = phrases_by_dictionary(token) if region(lang) == "jp" else (None, None, None)
        if ends is None: ends = boundaries(token, lang)             # Chinese; and Japanese without the dictionary
        quoted = set()
        for m in re.finditer("[" + re.escape(OPEN) + "]([^" + re.escape(OPEN + CLOSE) + "]{1,8})[" + re.escape(CLOSE) + "]", token): quoted.update(range(m.start() + 2, m.end() - 1))
        if chinese and chinese_words() is not None:                 # a phrase ends where BudouX says so AND a word ends; another word's end is cheap; inside a word is dearest
            edges = chinese_words().ends(token); begins = edges - ends if ends else set(); ends = (ends & edges) if ends else edges
        for i, u in enumerate(us):
            at += len(u)
            if i == len(us) - 1: kind = last
            elif (u[-1].isdigit() and kind_of(us[i + 1][0])) or (chinese and us[i + 1][0] in ENCLITICS): kind = "in"      # 30 分, 我们 的
            elif us[i + 1][0] in DOTS and u[-1] not in FREE_AFTER: kind = "in"                                            # …… trails the words before it
            elif at in quoted: kind = "in"                                                                                # a few words in brackets or quotation marks stay together
            elif u[-1] in FREE_AFTER or us[i + 1][0] in OPEN or at in ends: kind = "nat"
            elif begins is not None: kind = "soft" if at in begins else ("in" if at in edges else "mid")
            elif u[-1] in CLOSE: kind = "in" if ends else "soft"          # after a closing bracket: a particle may follow (」と), and only a phrase source knows
            else: kind = "soft" if (kind_of(u[-1]), kind_of(us[i + 1][0])) in WORD_BEGINS else "in"
            out.append((u, kind))
    return out
