#!/usr/bin/env python3
"""Scores the sources step of the career bench: per case, what the search proposed, which pages it read, which it kept as sources,
whether a kept source is of a kind the occupation is hired through, how many employers came from sources and how many of those
resolved to a board, a site, a size or a card. The addresses are printed for reading by hand, which is the real judgment.

    python3 careerbench-score-sources.py <bench dir> <results dir>
"""
import csv, json, pathlib, sys

def main():
    bench, results = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
    cases = {r["id"]: r for r in csv.DictReader(open(bench / "cases.tsv"), delimiter="\t")}
    for d in sorted(results.iterdir()):
        if not d.is_dir() or not (d / "search-companies.json").exists(): continue
        c = cases[d.name]
        lst = json.load(open(d / "search-companies.json")); lst = lst if isinstance(lst, list) else lst.get("companies", [])
        from_sources = [x for x in lst if x.get("from") == "sources"]
        resolved = [x for x in from_sources if x.get("feed") or x.get("site") or x.get("people") or x.get("card")]
        srcs = json.load(open(d / "sources.json")).get("sources", []) if (d / "sources.json").exists() else []
        mine = [s for s in srcs if s.get("found_by", "").endswith("Seeker " + d.name)]
        log = [json.loads(l) for l in open(d / "log.jsonl")] if (d / "log.jsonl").exists() else []
        where = [l["what"] for l in log if l.get("what", "").startswith("Where such employers are listed")]
        print(f"== {d.name} {c['occupation']} | {c['jurisdiction']} {c['language']} | hired through {c['how_filled']}; listed in {c['listed_in']}")
        for w in where: print("   ", w[:600])
        print(f"    sources kept by this search: {len(mine)} -> " + "; ".join(f"{s['id']} {s['kind']} {s['address'][:60]} (yield {s.get('yield_last')}, state {s.get('state')})" for s in mine))
        print(f"    employers from sources on the list: {len(from_sources)}, resolved to a board/site/size/card: {len(resolved)}; e.g. " + ", ".join(x['name'][:28] for x in from_sources[:8]))

if __name__ == "__main__":
    main()
