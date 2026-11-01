#!/usr/bin/env python3
"""Runs the career bench's mapping step on one half: for each case, hand over the input (file or description, then extras)
as a seeker in a scratch library, write the reading, and keep what came out: the wants with their states, the interview's
questions, the reading, the record. Scoring is separate (careerbench-score.py), so the raw outputs are kept.

    python3 run.py <bench dir> <half: build|holdout|adversarial> <scratch home> <results dir> [--only 02,04] [--search]

The launcher is RESEARCHZOSHO_LAUNCHER (default: the bench worktree's); the drive RESEARCHZOSHO_DRIVE and the model RESEARCHZOSHO_MODEL.
"""
import csv, json, os, pathlib, shutil, subprocess, sys, time

LAUNCHER = os.environ.get("RESEARCHZOSHO_LAUNCHER", "researchzosho")   # the launcher to drive: the installed command, or the one in a checkout
JAVA_OPTS = "--add-opens java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -Duser.home={home}"


def run(home, args, timeout=900):
    env = dict(os.environ, RESEARCHZOSHO_JAVA_OPTS=JAVA_OPTS.format(home=home), RESEARCHZOSHO_DRIVE=os.environ.get("RESEARCHZOSHO_DRIVE", "http://localhost:8080"),
               RESEARCHZOSHO_MODEL=os.environ.get("RESEARCHZOSHO_MODEL", "qwen3.8-27b"))
    t0 = time.time()
    p = subprocess.run([LAUNCHER] + args, capture_output=True, text=True, timeout=timeout, env=env, cwd=home)
    return p.returncode, (p.stdout + p.stderr)[-4000:], time.time() - t0


def main():
    bench, half, home, results = pathlib.Path(sys.argv[1]), sys.argv[2], pathlib.Path(sys.argv[3]), pathlib.Path(sys.argv[4])
    only = set(sys.argv[sys.argv.index("--only") + 1].split(",")) if "--only" in sys.argv else None
    home.mkdir(parents=True, exist_ok=True); results.mkdir(parents=True, exist_ok=True)
    rows = [r for r in csv.DictReader(open(bench / "cases.tsv"), delimiter="\t") if r["half"] == half and (only is None or r["id"] in only)]
    log = open(results / "run.log", "a")
    for r in rows:
        cid, name = r["id"], f"Seeker {r['id']}"
        out = results / cid
        if (out / "wants.json").exists(): continue
        out.mkdir(exist_ok=True)
        f_json, f_txt, f_extra, f_extra2 = bench / "cases" / f"{cid}.json", bench / "cases" / f"{cid}.txt", bench / "cases" / f"{cid}.extra.txt", bench / "cases" / f"{cid}.extra2.txt"
        steps = []
        if f_json.exists(): steps.append(["career", "provide", str(f_json), "--name", name])
        if f_txt.exists(): steps.append(["career", "provide", f_txt.read_text(encoding="utf-8").strip(), "--name", name])
        if f_extra.exists(): steps.append(["career", "provide", f_extra.read_text(encoding="utf-8").strip(), "--name", name])
        if f_extra2.exists(): steps.append(["career", "provide", f_extra2.read_text(encoding="utf-8").strip(), "--name", name])   # a second thing said later: the latest word
        steps.append(["career", "reading", "--name", name])
        steps.append(["career", "interview", "--name", name])   # starts the interview: the questions, the first one asked, then interview.json exists
        if "--search" in sys.argv: steps.append(["career", "search", "--here", "--name", name])   # the whole search in the terminal, for the sources step
        timings = {}
        for st in steps:
            rc, text, dt = run(home, st, timeout=2400 if st[1] == "search" else 900)
            timings[st[1] + ("-extra" if st is steps[-2] and f_extra.exists() and st[1] == "provide" else "")] = round(dt, 1)
            (out / f"{st[1]}.out").open("a", encoding="utf-8").write(f"## {' '.join(x[:60] for x in st)}\nrc={rc} {dt:.1f}s\n{text}\n")
            log.write(f"{time.strftime('%H:%M:%S')} {cid} {st[1]} rc={rc} {dt:.1f}s\n"); log.flush()
            if rc != 0 and st[1] == "provide": break
        slug = name.lower().replace(" ", "-")
        rec = home / "researchzosho-library" / "career" / slug
        for fn in ("wants.json", "interview.json", "reading.md", "record.json", "evidence.json", "search.json", "wants-draft.txt"):
            if (rec / fn).exists(): shutil.copy(rec / fn, out / fn)
        for fn in ("search/companies.json", "search/cut.json", "search/openings.json", "search/companies-first.json", "log.jsonl"):
            if (rec / fn).exists(): shutil.copy(rec / fn, out / fn.replace("/", "-"))
        srcs = home / "researchzosho-library" / "catalog" / "sources.json"
        if srcs.exists(): shutil.copy(srcs, out / "sources.json")
        json.dump({"id": cid, "timings": timings}, open(out / "meta.json", "w"))
    log.close()
    print("done:", sum(1 for r in rows if (results / r["id"] / "wants.json").exists()), "of", len(rows))


if __name__ == "__main__":
    main()
