"""Hyphenation by Liang's algorithm on TeX pattern files (hyph-<language>.pat.txt, hyph-<language>.hyp.txt).
No library is used; the pattern files are data fetched by the manifest. A language with no pattern file gets no
hyphenation: its words are never broken."""
import os
import re

class Hyphenator:
    def __init__(self, pattern_file, exception_file=None, left=3, right=3):
        self.left, self.right = left, right; self.patterns = {}; self.exceptions = {}
        with open(pattern_file, encoding="utf-8") as f:
            for line in f:
                p = line.strip()
                if not p or p.startswith("%"): continue
                letters = re.sub(r"\d", "", p); vals = [0] * (len(letters) + 1); i = 0
                for ch in p:
                    if ch.isdigit(): vals[i] = int(ch)
                    else: i += 1
                self.patterns[letters] = vals
        if exception_file and os.path.exists(exception_file):
            with open(exception_file, encoding="utf-8") as f:
                for line in f:
                    w = line.strip()
                    if not w: continue
                    cuts, n = [], 0
                    for ch in w:
                        if ch == "-": cuts.append(n)
                        else: n += 1
                    self.exceptions[w.replace("-", "")] = cuts
        self.longest = max((len(k) for k in self.patterns), default=0)

    def positions(self, word):
        """Places where the word may break: indexes into the word, each with at least `left` letters before it and
        `right` after it."""
        w = word.lower(); n = len(w)
        if n < self.left + self.right: return []
        if w in self.exceptions: cuts = self.exceptions[w]
        else:
            work = "." + w + "."; pts = [0] * (len(work) + 1)
            for i in range(len(work)):
                for j in range(i + 1, min(len(work), i + self.longest) + 1):
                    v = self.patterns.get(work[i:j])
                    if v:
                        for k, x in enumerate(v):
                            if x > pts[i + k]: pts[i + k] = x
            cuts = [i - 1 for i in range(2, len(work) - 1) if pts[i] % 2 == 1]
        return [c for c in cuts if self.left <= c <= n - self.right]

def load(folder, language):
    """The hyphenator for a language code ('en', 'en-us', 'de', ...) from a folder of pattern files, or None."""
    if not folder or not language: return None
    code = language.lower().replace("_", "-")
    names = [code] + ([code + "-us"] if code == "en" else []) + [code.split("-")[0]]
    for nm in names:
        p = os.path.join(folder, f"hyph-{nm}.pat.txt")
        if os.path.exists(p): return Hyphenator(p, os.path.join(folder, f"hyph-{nm}.hyp.txt"))
    return None
