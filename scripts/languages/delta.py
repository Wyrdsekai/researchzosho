#!/usr/bin/env python3
"""Writes what each language still has to translate after the program's words changed.

usage: scripts/languages/delta.py <folder> [--context <file>]

For every language file beside the Japanese one, <folder>/<code>.todo.json lists the keys the Japanese file has and that file lacks:
[{"i": number, "en": the English key, "ja": the Japanese, "context": where it shows}, …]. The context comes from <file>, a JSON object
of English key to one line saying where the string shows and what fills its places; whoever changes the code writes it, because a
translator sees the strings and nothing else. A translator writes <folder>/<code>.new.json as {"<i>": "<translation>"} and apply.py
puts it in. Keys a language file has that the Japanese file no longer has are counted here and dropped by apply.py."""
import glob, json, os, sys

CHROME = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'librarian', 'src', 'main', 'resources', 'org', 'researchzosho', 'librarian', 'chrome')


def main(argv):
    if len(argv) not in (1, 3) or (len(argv) == 3 and argv[1] != '--context'): print(__doc__); return 2
    out = argv[0]; os.makedirs(out, exist_ok=True)
    context = json.load(open(argv[2], encoding='utf-8')) if len(argv) == 3 else {}
    ja = json.load(open(os.path.join(CHROME, 'ja.json'), encoding='utf-8'))
    for f in sorted(glob.glob(os.path.join(CHROME, '*.json'))):
        code = os.path.basename(f)[:-5]
        if code == 'ja': continue
        d = json.load(open(f, encoding='utf-8'))
        todo = [{'i': i, 'en': k, 'ja': ja[k], **({'context': context[k]} if k in context else {})} for i, k in enumerate(k for k in ja if k not in d)]
        gone = [k for k in d if k not in ja]
        json.dump(todo, open(os.path.join(out, code + '.todo.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
        print('%s: %d to translate, %d no longer used' % (code, len(todo), len(gone)))
    return 0


if __name__ == '__main__': sys.exit(main(sys.argv[1:]))
