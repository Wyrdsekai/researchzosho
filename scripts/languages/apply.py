#!/usr/bin/env python3
"""Puts a translator's new strings into a language's file and checks it.

usage: scripts/languages/apply.py [--draft] <folder> <code> [code ...]

Reads <folder>/<code>.todo.json (written by delta.py) and <folder>/<code>.new.json ({"<i>": "<translation>"}), and optionally
<folder>/<code>.fix.json ({"<English key>": "<better translation>"} for strings the file already has). Writes the language's file with
exactly the Japanese file's keys, in its order and layout, and runs check.py on it. Nothing is written when a number is missing.
With --draft the result goes to <folder>/<code>.json and the tree is left as it is: a translator's own check before handing in."""
import json, os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
CHROME = os.path.join(HERE, '..', '..', 'librarian', 'src', 'main', 'resources', 'org', 'researchzosho', 'librarian', 'chrome')


def main(argv):
    draft = argv[:1] == ['--draft']
    if draft: argv = argv[1:]
    if len(argv) < 2: print(__doc__); return 2
    folder, rc = argv[0], 0
    ja = json.load(open(os.path.join(CHROME, 'ja.json'), encoding='utf-8'))
    for code in argv[1:]:
        path = os.path.join(CHROME, code + '.json')
        d = json.load(open(path, encoding='utf-8')) if os.path.exists(path) else {}
        todo = json.load(open(os.path.join(folder, code + '.todo.json'), encoding='utf-8'))
        new = json.load(open(os.path.join(folder, code + '.new.json'), encoding='utf-8'))
        lost = [t['i'] for t in todo if str(t['i']) not in new]
        if lost: print('%s: NOT WRITTEN, %d numbers have no translation: %s' % (code, len(lost), lost[:20])); rc = 1; continue
        for t in todo: d[t['en']] = new[str(t['i'])]
        fix = os.path.join(folder, code + '.fix.json')
        if os.path.exists(fix): d.update({k: v for k, v in json.load(open(fix, encoding='utf-8')).items() if k in ja})
        still = [k for k in ja if k not in d]
        if still: print('%s: NOT WRITTEN, %d keys of the Japanese file are in neither the file nor the new strings (run delta.py again)' % (code, len(still))); rc = 1; continue
        dest = os.path.join(folder, code + '.json') if draft else path
        open(dest, 'w', encoding='utf-8').write(json.dumps({k: d[k] for k in ja}, ensure_ascii=False, indent=1) + '\n')
        rc = subprocess.call([sys.executable, os.path.join(HERE, 'check.py')] + (['--file', dest] if draft else [code])) or rc
    return rc


if __name__ == '__main__': sys.exit(main(sys.argv[1:]))
