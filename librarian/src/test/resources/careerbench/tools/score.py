#!/usr/bin/env python3
"""Scores the mapping step of the career bench from the raw outputs careerbench-run.py kept: per case, which of the minimum to map
a seeker (what work, at what level, for what kind of employer, where, remote or not, what they rule out; and the newer ones, the
arrangement, mobility, the right to work, a sector, evidence) the reading reached from the input alone, and whether the interview's
questions go to what the input left out. Deterministic where it can be (word matching against the case's own sampled values); the
raw files stay for reading by hand.

    python3 score.py <bench dir> <results dir>

A case with a truth file (cases/<id>.truth.json, the adversarial ones) is also judged on the trap it sets: each expectation in it
is checked against the wants, the keep-outs, the names and the interview, and reported by trap.
"""
import csv, json, pathlib, re, sys

TOPICS = ["domain", "kind of work", "role", "kind of employer", "company size", "company stage", "culture", "place", "mobility", "remote", "arrangement", "right to work", "pace", "pay", "ownership"]


def has(text, words):
    t = (text or "").lower()
    return any(w and w.lower() in t for w in words)


def trap_checks(d, expect):
    """Each expectation of an adversarial case's truth, checked against what the mapping produced; a name → whether it held."""
    wants = {w["dimension"]: w for w in json.load(open(d / "wants.json")).get("dimensions", [])}
    want = lambda t: (wants.get(t, {}).get("want", "") or "")
    state = lambda t: wants.get(t, {}).get("state", "open")
    search = json.load(open(d / "search.json")) if (d / "search.json").exists() else {}
    named = " ".join(n.get("name", "") for n in search.get("named", []) if isinstance(n, dict)) + " " + " ".join(c.get("name", "") for c in search.get("companies", []) if isinstance(c, dict))
    kept_out = " ".join(k.get("text", "") for k in search.get("keep_out", []) if isinstance(k, dict))
    tags = ""
    if (d / "interview.json").exists():
        iv = json.load(open(d / "interview.json")); tags = " ".join(q.get("tag", "") for q in iv.get("questions", []) if isinstance(q, dict))
    all_wants = json.dumps(wants, ensure_ascii=False)
    out = {}
    for key, val in expect.items():
        if key == "work_words": out[key] = any(state(t) != "open" and has(want(t), val) for t in ("role", "domain", "kind of work"))
        elif key == "place_decided": out[key] = state("place") == "decided" and has(want("place"), [val])
        elif key == "place_not": out[key] = not has(want("place"), val)
        elif key == "mobility_open": out[key] = (state("mobility") == "open") == bool(val) or (bool(val) and not has(want("mobility"), ["move", "abroad", "anywhere", "commute"]))
        elif key == "named_not": out[key] = not has(named, val) and not has(all_wants, val)
        elif key == "named_contains": out[key] = all(has(named, [v]) for v in val)
        elif key == "role_not": out[key] = not has(want("role"), val) and not has(want("kind of work"), val)
        elif key == "remote_decided_contains": out[key] = state("remote") == "decided" and has(want("remote"), val)
        elif key == "keep_out_contains": out[key] = all(has(kept_out, [v]) for v in val)
        elif key == "keep_out_not": out[key] = not has(kept_out, val)
        elif key == "right_not": out[key] = not has(want("right to work"), val)
        elif key == "asks_right_minimum": out[key] = ("the minimum: right to work" in tags) == bool(val)
        else: out[key] = False
    return out


def main():
    bench, results = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
    cases = {r["id"]: r for r in csv.DictReader(open(bench / "cases.tsv"), delimiter="\t")}
    rows = []
    for d in sorted(results.iterdir()):
        if not d.is_dir() or not (d / "wants.json").exists(): continue
        c = cases[d.name]
        wants = {w["dimension"]: w for w in json.load(open(d / "wants.json")).get("dimensions", [])}
        states = {t: wants.get(t, {}).get("state", "open") for t in TOPICS}
        text_of = lambda t: wants.get(t, {}).get("want", "")
        occ_words = [w for w in re.split(r"\W+", c["occupation"]) if len(w) > 3 and w not in ("finishing", "engineer", "worker", "assistant", "manager", "operator")]
        work_mapped = any(states[t] != "open" and has(text_of(t), occ_words or [c["occupation"]]) for t in ("role", "domain", "kind of work"))   # the work is mapped when a topic that is not open names it
        level_said = c["shape"] in ("stepping up", "stepping down") or c["years"] == "finishing study" or c["level_wanted"] in ("senior", "executive")   # the input states the level only there: a plain title does not tell entry from mid
        level_mapped = (not level_said) or (states["role"] != "open" and has(text_of("role") + " " + text_of("kind of work"), {"entry": ["entry", "junior", "first", "graduate", "starting", "return"], "mid": ["mid", c["occupation"]], "senior": ["senior", "lead", "step up", "move up", "next level"], "executive": ["head", "director", "chief", "vice president", "partner", "principal", "executive", "step up", "move up"]}[c["level_wanted"]]))
        place_given = c["completeness"] != "place missing"
        said_place = place_given and c["input_form"] in ("description",)   # a description says "I want to work in X"; a file only carries where the roles were, which is a guess by design
        place_mapped = (states["place"] == "decided" if said_place else states["place"] in ("decided", "leaning")) if place_given else states["place"] == "open"
        remote_said = c["remote_wanted"] != "no"
        remote_mapped = states["remote"] != "open" if remote_said else True
        size_said = c["size_wanted"] != "none"
        size_mapped = states["company size"] != "open" if size_said else True
        arr_said = c["arrangement"] != "full-time"; arr_mapped = states.get("arrangement", "open") != "open" if arr_said else True
        mob_said = c["mobility"] != "stay"; mob_mapped = states.get("mobility", "open") != "open" if mob_said else True
        right_said = c["right_to_work"] != "none"; right_mapped = states.get("right to work", "open") != "open" if right_said else True
        right_open = states.get("right to work", "open") == "open"   # an ask is due only where the input left the topic open; where the person said it, the draft decides it and asking again is wrong
        mob_in_place = mob_said and states["place"] == "decided" and has(text_of("place"), ["move", "abroad", "anywhere", "country", "ins Ausland", "déménag", "引っ越", "mudar", "mudaría", "overseas", "within an hour", "einer Stunde", "une heure", "1時間", "uma hora", "una hora"])
        town = c["jurisdiction"] and {"US/Ohio": "Columbus", "US/Texas": "Austin", "UK": "Manchester", "CA/Ontario": "Toronto", "DE": "Leipzig", "FR": "Lyon", "JP": "Osaka", "BR": "Belo Horizonte", "IN": "Pune", "AU": "Perth", "ES": "Valencia"}.get(c["jurisdiction"], "")
        commute_ok = c["mobility"] == "commute" and states["place"] == "decided" and has(text_of("place"), [town]) and not has(text_of("place"), ["within an hour", "einer Stunde", "une heure", "1時間", "uma hora", "una hora"]) and states["mobility"] != "open"
        keep = [k for k in c["keep_outs"].split(",") if k]
        search = json.load(open(d / "search.json")) if (d / "search.json").exists() else {}
        kept_out = [k.get("text", "") for k in search.get("keep_out", [])]
        keep_mapped = all(has(" ".join(kept_out) + " " + json.dumps(wants), [k]) for k in keep) if keep else True
        qs = []
        if (d / "interview.json").exists():
            iv = json.load(open(d / "interview.json")); qs = [q for q in iv.get("questions", []) if isinstance(q, dict)]
        if not qs and (d / "reading.md").exists():   # the reading ends with its questions, numbered, each with a tag in parentheses before the evidence marks
            md = (d / "reading.md").read_text(encoding="utf-8")
            at = md.find("# Questions")
            for line in (md[at:] if at >= 0 else "").splitlines():
                m = re.match(r"^\s*\d+\.\s+(.*)$", line)
                if m:
                    body = m.group(1); tag = ""
                    t = re.search(r"\(([^()]+)\)\s*(\[E[^\]]*\]\s*)*$", body)
                    if t: tag = t.group(1)
                    qs.append({"text": body, "tag": tag})
        qtext = " ".join(q.get("text", "") + " " + q.get("tag", "") for q in qs)
        tags = " ".join(q.get("tag", "") for q in qs)
        asks_place = ("the minimum: place" in tags) or bool(re.search(r"(?i)(where (do|would|will) you (want|like|prefer) to (work|live|be)|where you (want|would like) to work|which (city|town|region|country)|what (city|town|region|country)|where to work|where would you (work|move)|どこで働|où (voulez|souhaitez)|dónde (quieres|te gustaría)|onde (quer|gostaria)|wo (möchten|wollen) sie)", qtext))
        asks_kind = has(qtext, ["kind of", "what work", "role", "level", "seat", "title", "lead", "what do you want to do"])
        asks_employer = ("the minimum: kind of employer" in tags) or has(qtext, ["employer", "hospital", "lab", "firm", "company", "organisation", "organization", "school", "agency"])
        asks_right = ("the minimum: right to work" in tags) or has(qtext, ["visa", "sponsor", "licen", "registration", "right to work", "permit"])
        asks_arrangement = ("the minimum: arrangement" in tags) or has(qtext, ["full-time", "part-time", "contract", "freelance", "temporary", "apprentice", "hours"])
        asks_mobility = ("the minimum: mobility" in tags) or has(qtext, ["move", "relocat", "commute", "abroad", "willing to"])
        rows.append({"id": c["id"], "occupation": c["occupation"][:28], "level": c["level_wanted"], "form": c["input_form"], "completeness": c["completeness"], "lang": c["language"],
                     "work": work_mapped, "level_ok": level_mapped, "place": place_mapped, "remote": remote_mapped, "size": size_mapped, "keep": keep_mapped, "arr": arr_mapped, "mob": mob_mapped, "rightw": right_mapped, "mob_in_place": mob_in_place, "commute_ok": commute_ok,
                     "decided": sum(1 for s in states.values() if s == "decided"), "guess": sum(1 for s in states.values() if s == "leaning"), "open": sum(1 for s in states.values() if s == "open"),
                     "questions": len(qs), "asks_place": asks_place, "asks_kind": asks_kind, "asks_employer": asks_employer, "asks_right": asks_right, "right_open": right_open, "asks_arrangement": asks_arrangement, "asks_mobility": asks_mobility,
                     "place_missing": not place_given, "kind_missing": c["completeness"] == "kind or level missing", "right": c["right_to_work"], "arrangement": c["arrangement"], "mobility": c["mobility"]})
    traps = []
    for d in sorted(results.iterdir()):
        truth = bench / "cases" / f"{d.name}.truth.json"
        if not d.is_dir() or not truth.exists() or not (d / "wants.json").exists(): continue
        tr = json.load(open(truth)); traps.append((d.name, tr["trap"], trap_checks(d, tr["expect"])))
    if traps:
        print("adversarial cases:")
        for cid, trap, checks in traps:
            ok = all(v for v in checks.values())
            print(f"  {cid} {trap}: {'PASS' if ok else 'FAIL'}  " + "; ".join(f"{k}={'ok' if v else 'MISS'}" for k, v in checks.items()))
        print(f"  traps held: {sum(1 for _, _, c in traps if all(c.values()))}/{len(traps)}")
    if not rows: print("no results yet"); return
    cols = list(rows[0].keys())
    with open(results / "scores.tsv", "w") as f:
        f.write("\t".join(cols) + "\n")
        for r in rows: f.write("\t".join(str(r[k]) for k in cols) + "\n")
    n = len(rows)
    pct = lambda k, cond=lambda r: True: f"{sum(1 for r in rows if cond(r) and r[k])}/{sum(1 for r in rows if cond(r))}"
    print(f"cases scored: {n}")
    print(f"  the work mapped (occupation words in kind of work / role / domain): {pct('work')}")
    print(f"  the level mapped, where the input states it:                       {pct('level_ok', lambda r: cases[r['id']]['shape'] in ('stepping up', 'stepping down') or cases[r['id']]['years'] == 'finishing study' or cases[r['id']]['level_wanted'] in ('senior', 'executive'))}")
    print(f"  commute: the town decided as place AND mobility mapped:            {pct('commute_ok', lambda r: cases[r['id']]['mobility'] == 'commute' and cases[r['id']]['completeness'] != 'place missing')}")
    print(f"  the place: decided when given, open when not:                      {pct('place')}")
    print(f"  remote mapped when said:                                           {pct('remote', lambda r: cases[r['id']]['remote_wanted'] != 'no')}")
    print(f"  size mapped when said:                                             {pct('size', lambda r: cases[r['id']]['size_wanted'] != 'none')}")
    print(f"  keep-outs captured when said:                                      {pct('keep', lambda r: cases[r['id']]['keep_outs'])}")
    print(f"  arrangement mapped when said: {pct('arr', lambda r: cases[r['id']]['arrangement'] != 'full-time')}; mobility mapped when said: {pct('mob', lambda r: cases[r['id']]['mobility'] != 'stay')}; right to work mapped when it matters: {pct('rightw', lambda r: cases[r['id']]['right_to_work'] != 'none')}")
    print(f"  mobility filed under place as decided (the old trap):              {pct('mob_in_place', lambda r: cases[r['id']]['mobility'] != 'stay')}")
    print(f"  questions per case: mean {sum(r['questions'] for r in rows) / n:.1f}; asks the place when it was missing: {pct('asks_place', lambda r: r['place_missing'])}; asks the kind or level when missing: {pct('asks_kind', lambda r: r['kind_missing'])}")
    print(f"  asks about the employer kind: {pct('asks_employer')}; the right to work when it matters and the input left it open: {pct('asks_right', lambda r: r['right'] != 'none' and r['right_open'])}; the arrangement when not full-time: {pct('asks_arrangement', lambda r: r['arrangement'] != 'full-time')}; mobility when not staying: {pct('asks_mobility', lambda r: r['mobility'] != 'stay')}")
    print(f"  states per case: decided {sum(r['decided'] for r in rows) / n:.1f}, guess {sum(r['guess'] for r in rows) / n:.1f}, open {sum(r['open'] for r in rows) / n:.1f} of {len(TOPICS)} topics")
    print("  by input form:", {f: f"{sum(1 for r in rows if r['form'] == f and r['work'])}/{sum(1 for r in rows if r['form'] == f)} work mapped" for f in ("file", "description", "file+extra")})
    print("  by language:", {l: f"{sum(1 for r in rows if r['lang'] == l and r['work'])}/{sum(1 for r in rows if r['lang'] == l)}" for l in sorted({r['lang'] for r in rows})})


if __name__ == "__main__":
    main()
