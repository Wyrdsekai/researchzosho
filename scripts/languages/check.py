#!/usr/bin/env python3
"""Checks the interface's word files against the Japanese one, which is the reference for the list of keys.

usage: scripts/languages/check.py [code ...]      (every language when none is named)
       scripts/languages/check.py --file <a file> (a file outside the tree, such as a translator's draft)

A file can ship when it has exactly the reference's keys and, in every value, what must stay as written: the {0} placeholders, HTML
tags and entities, text in backticks, --flags, /commands, the command's own name, setting names, addresses, and the marker the model
writes ([general knowledge]). A LOOK line is a name, path or file name that is not in the translation as written: read each one.
Exit 0 when every file checked can ship."""
import collections, glob, json, os, re, sys

CHROME = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'librarian', 'src', 'main', 'resources', 'org', 'researchzosho', 'librarian', 'chrome')
KEEP = [(r'\{\d+\}', 'placeholder'), (r'</?(?:a|b|i|em|strong|code|br|span|p|kbd|small|ul|li|div)\b[^>]*>', 'HTML tag'), (r'&[a-z]+;|&#\d+;', 'entity'),
        (r'`[^`]*`', 'code span'), (r'(?<![\w-])--[a-z][a-z0-9-]*', 'flag'), (r'(?<![\w/.:<])/[a-z][a-z_-]{2,}(?![\w/.])', 'slash command'),
        (r'\bresearchzosho\b|\bzosho\b', 'the command'), (r'\b[A-Z][A-Z0-9]+_[A-Z0-9_]+\b', 'setting name'), (r'https?://[^\s)\]」』"]+', 'address'),
        (r'\[general knowledge\]', 'the marker the model writes')]
SOFT = [(r'\b[a-z]+_[a-z_]+\b', 'a name with an underscore'), (r'(?<![\w/])~?/[\w~-]+(?:/[\w~-]+)+', 'a path'),
        (r'\b[\w-]+\.(?:md|json|jsonl|txt|pdf|docx|epub|html|yaml|sh|py|gguf)\b', 'a file name')]


def bag(rx, s): return collections.Counter(re.findall(rx, s))


def check(path, ref):
    name = os.path.basename(path)
    try: d = json.load(open(path, encoding='utf-8'))
    except Exception as e: print(name + ': NOT JSON:', e); return False
    faults, looks, same = [], [], 0
    missing = [k for k in ref if k not in d]; extra = [k for k in d if k not in ref]
    faults += [('missing key', k) for k in missing[:40]] + [('key not in the reference', k) for k in extra[:40]]
    for k0, v in d.items():
        if k0 not in ref: continue
        k = re.sub(r' ⟦[^⟧]*⟧$', '', k0)   # a key with a meaning beside it: the meaning is for the translator, not for the text
        if not isinstance(v, str) or (not v.strip('　 \t\n') and k.strip()): faults.append(('empty', k0)); continue
        if '⟦' in v or '⟧' in v: faults.append(('the meaning marker ⟦…⟧ belongs to the key only', k0))
        if re.search(r'[(（][yYsSjJoOдДнН]/[nN][)）]\s*$', v): faults.append(('a yes/no hint at the end: the program adds it', k0))
        for rx, what in KEEP:
            a, b = bag(rx, k), bag(rx, v)
            if a != b: faults.append(('%s changed: %s missing, %s added' % (what, dict(a - b) or '', dict(b - a) or ''), k0))
        for rx, what in SOFT:
            a, b = bag(rx, k), bag(rx, v)
            if a - b: looks.append(('%s is not in the translation as written: %s' % (what, dict(a - b)), k0))
        if k[:1].isspace() != v[:1].isspace() and not re.match(r'[　-鿿가-힯]', v[:1] or ' '): faults.append(('leading blank differs', k0))
        if k.endswith('\n') != v.endswith('\n'): faults.append(('final line break differs', k0))
        if v == k and re.search(r'[A-Za-z]{4,}', re.sub(r'`[^`]*`|\{\d+\}|researchzosho|--[a-z-]+|/[a-z_-]+|[A-Z][A-Za-z0-9]+', '', k)): same += 1
    for what, k in looks[:40]: print('LOOK ', what, '|', k[:110].replace('\n', '\\n'))
    for what, k in faults[:60]: print('FAULT', what, '|', k[:110].replace('\n', '\\n'))
    print('%s: %d keys of %d, %d missing, %d extra, %d faults, %d to look at, %d left in English' % (name, len(d), len(ref), len(missing), len(extra), len(faults), len(looks), same))
    return not faults


def main(argv):
    ref = json.load(open(os.path.join(CHROME, 'ja.json'), encoding='utf-8'))
    if argv[:1] == ['--file']: return 0 if check(argv[1], ref) else 1
    codes = argv or sorted(os.path.basename(f)[:-5] for f in glob.glob(os.path.join(CHROME, '*.json')) if not f.endswith('ja.json'))
    return 0 if all([check(os.path.join(CHROME, c + '.json'), ref) for c in codes]) else 1


if __name__ == '__main__': sys.exit(main(sys.argv[1:]))
