#!/usr/bin/env python3
"""The test suite's time, read from the test task's reports, held to its budget.

Run, after a whole run of the suite (cleanTest first, so that no report is left from an earlier run):

    python3 scripts/suite-time.py                  # the reports of :librarian:test and :client:test
    python3 scripts/suite-time.py DIR [DIR ...]    # or the folders of JUnit XML reports given

It prints the suite's time (the sum of every test class's own time, as the reports give it), the slowest classes and the slowest
tests, and every class over its limit. The limits are in suite-time-limits.txt beside this script: the suite's budget, the limit of
any one class, and the few classes allowed more, each with its reason.

Exit codes: 0 within the budget; 1 a class over its limit, or the suite over its budget; 2 nothing to read (no report, or a
limits file it cannot read), so that a missing report is never taken for a suite within its budget.

Standard library only: it runs wherever python3 does.
"""
from __future__ import annotations

import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DEFAULT_DIRS = [os.path.join(ROOT, "librarian", "build", "test-results", "test"), os.path.join(ROOT, "client", "build", "test-results", "test")]
DEFAULT_LIMITS = os.path.join(HERE, "suite-time-limits.txt")


class LimitsError(Exception):
    pass


def read_limits(path: str):
    """The limits file: 'suite <seconds>', 'class <seconds>', and '<TestClass> <seconds> <reason>' for a class allowed more."""
    suite = cls = None
    allowed: dict[str, tuple[float, str]] = {}
    try:
        lines = open(path, encoding="utf-8").read().splitlines()
    except OSError as e:
        raise LimitsError(f"cannot read the limits file {path}: {e.strerror}")
    for n, raw in enumerate(lines, 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split(None, 2)
        if len(parts) < 2:
            raise LimitsError(f"{path}:{n}: a name and a number of seconds: {raw!r}")
        try:
            seconds = float(parts[1])
        except ValueError:
            raise LimitsError(f"{path}:{n}: not a number of seconds: {parts[1]!r}")
        if parts[0] == "suite":
            suite = seconds
        elif parts[0] == "class":
            cls = seconds
        else:
            if len(parts) < 3 or not parts[2].strip():
                raise LimitsError(f"{path}:{n}: a class allowed more says why: {raw!r}")
            allowed[parts[0]] = (seconds, parts[2].strip())
    if suite is None or cls is None:
        raise LimitsError(f"{path}: the file names the suite's budget ('suite <seconds>') and the limit of a class ('class <seconds>')")
    return suite, cls, allowed


def read_reports(dirs: list[str]):
    """Every test class of the reports: (name, seconds, tests, failed, skipped, timestamp, [(test seconds, test name)])."""
    classes = []
    for d in dirs:
        for f in sorted(glob.glob(os.path.join(d, "TEST-*.xml"))):
            try:
                r = ET.parse(f).getroot()
            except ET.ParseError as e:
                print(f"suite-time: {f} cannot be read as a report ({e}); it is left out", file=sys.stderr)
                continue
            tests = [(float(c.get("time") or 0), c.get("name") or "") for c in r.iter("testcase")]
            classes.append((r.get("name") or os.path.basename(f), float(r.get("time") or 0), int(r.get("tests") or 0),
                            int(r.get("failures") or 0) + int(r.get("errors") or 0), int(r.get("skipped") or 0), r.get("timestamp") or "", tests))
    return classes


def allowance(name: str, allowed: dict[str, tuple[float, str]]):
    """The limit a class is allowed, by its full name or its simple name; None when it has none of its own."""
    if name in allowed:
        return allowed[name]
    simple = name.rsplit(".", 1)[-1]
    return allowed.get(simple)


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(description="The test suite's time, held to its budget.")
    p.add_argument("dirs", nargs="*", help="folders of JUnit XML reports (default: the :librarian:test and :client:test reports)")
    p.add_argument("--limits", default=DEFAULT_LIMITS, help="the limits file (default: suite-time-limits.txt beside this script)")
    p.add_argument("--top", type=int, default=15, help="how many of the slowest classes and tests to print (default 15)")
    a = p.parse_args(argv)
    try:
        budget, class_limit, allowed = read_limits(a.limits)
    except LimitsError as e:
        print(f"suite-time: {e}", file=sys.stderr)
        return 2
    dirs = a.dirs or DEFAULT_DIRS
    classes = read_reports(dirs)
    if not classes:
        print("suite-time: no test report in " + ", ".join(dirs) + ": run the suite first", file=sys.stderr)
        return 2

    total = sum(c[1] for c in classes)
    tests = sum(c[2] for c in classes)
    failed = sum(c[3] for c in classes)
    skipped = sum(c[4] for c in classes)
    stamps = sorted(c[5] for c in classes if c[5])
    print(f"The suite: {len(classes)} classes, {tests} tests ({failed} failed, {skipped} skipped), {total:.1f} s of test time; budget {budget:.0f} s.")
    if stamps:
        print(f"Reports from {stamps[0]} to {stamps[-1]}.")

    print(f"\nThe {min(a.top, len(classes))} slowest classes:")
    for name, secs, n, _, _, _, _ in sorted(classes, key=lambda c: -c[1])[:a.top]:
        own = allowance(name, allowed)
        print(f"  {secs:7.1f} s  {n:4d} tests  {name}" + (f"  (allowed {own[0]:.0f} s)" if own else ""))

    every = [(t, f"{c[0]}.{name}") for c in classes for t, name in c[6]]
    print(f"\nThe {min(a.top, len(every))} slowest tests:")
    for secs, name in sorted(every, key=lambda x: -x[0])[:a.top]:
        print(f"  {secs:7.1f} s  {name}")

    over = []
    for name, secs, *_ in classes:
        own = allowance(name, allowed)
        limit = own[0] if own else class_limit
        if secs > limit:
            over.append(f"  {name}: {secs:.1f} s, over its limit of {limit:.0f} s" + (f" ({own[1]})" if own else ""))
    unused = [k for k in allowed if not any(c[0] == k or c[0].rsplit(".", 1)[-1] == k for c in classes)]
    if unused:
        print("\nAllowed more in the limits file, and in no report: " + ", ".join(unused) + " (a class renamed or removed: take its line out).")
    if over or total > budget:
        print("\nOver the budget:")
        for line in over:
            print(line)
        if total > budget:
            print(f"  the suite: {total:.1f} s, over its budget of {budget:.0f} s")
        return 1
    print(f"\nWithin the budget: the suite {total:.1f} of {budget:.0f} s, and every class within its limit.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
