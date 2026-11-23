#!/usr/bin/env python3
"""Tests of the suite's time budget (scripts/suite-time.py).

Run: python3 scripts/lib/test_suite_time.py

Each case writes JUnit XML reports and a limits file of its own into a throwaway folder and runs the script on them, as a person runs
it. What is pinned: the time is the sum of the classes' own times; a class over the limit fails by name, and a class allowed more is
held to its own limit and its reason is said; the suite over its budget fails; and nothing to read (no report, a limits file that
cannot be read) is exit 2, never a pass. Standard library only, as the script is.
"""
from __future__ import annotations

import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(os.path.dirname(HERE), "suite-time.py")

FAILURES: list[str] = []


def report(folder: str, name: str, seconds: float, tests: list[tuple[str, float]], failures: int = 0) -> None:
    cases = "".join(f'  <testcase name="{t}" classname="{name}" time="{s}"/>\n' for t, s in tests)
    xml = (f'<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="{name}" tests="{len(tests)}" skipped="0" failures="{failures}" errors="0" '
           f'timestamp="2026-11-23T10:00:00Z" hostname="a-machine" time="{seconds}">\n  <properties/>\n{cases}</testsuite>\n')
    with open(os.path.join(folder, f"TEST-{name}.xml"), "w", encoding="utf-8") as f:
        f.write(xml)


def run(reports: dict[str, tuple[float, list[tuple[str, float]]]], limits: str | None, extra: list[str] | None = None):
    """Writes the reports and the limits file, runs the script on them; (exit code, what it printed)."""
    with tempfile.TemporaryDirectory() as d:
        folder = os.path.join(d, "reports")
        os.makedirs(folder)
        for name, (seconds, tests) in reports.items():
            report(folder, name, seconds, tests)
        args = [sys.executable, SCRIPT]
        if limits is not None:
            path = os.path.join(d, "limits.txt")
            with open(path, "w", encoding="utf-8") as f:
                f.write(limits)
            args += ["--limits", path]
        else:
            args += ["--limits", os.path.join(d, "no-such-limits.txt")]
        args += (extra or []) + [folder]
        p = subprocess.run(args, capture_output=True, text=True)
        return p.returncode, p.stdout + p.stderr


def check(what: str, ok: bool, said: str) -> None:
    if not ok:
        FAILURES.append(f"{what}\n{said}")


LIMITS = "# a comment\nsuite 100\nclass 20\nSlowTest 45 two processes of the program, each started in full\n"
WITHIN = {"org.example.FastTest": (3.5, [("a", 1.5), ("b", 2.0)]), "org.example.OtherTest": (10.0, [("c", 10.0)])}


def test_the_time_is_the_sum_of_the_classes_and_within_is_exit_0():
    rc, out = run(WITHIN, LIMITS)
    check("within: exit 0", rc == 0, out)
    check("the time is the sum of the classes' times", "13.5 s of test time" in out and "2 classes, 3 tests" in out, out)
    check("the slowest class first", out.index("org.example.OtherTest") < out.index("org.example.FastTest"), out)
    check("the slowest tests are listed by class and name", "org.example.OtherTest.c" in out, out)


def test_a_class_over_the_limit_fails_by_name():
    rc, out = run({**WITHIN, "org.example.HeavyTest": (25.0, [("d", 25.0)])}, LIMITS)
    check("a class over the limit: exit 1", rc == 1, out)
    check("it is named with its time and the limit", "org.example.HeavyTest: 25.0 s, over its limit of 20 s" in out, out)
    check("and nothing else is", "FastTest: " not in out.split("Over the budget:")[-1], out)


def test_a_class_allowed_more_is_held_to_its_own_limit():
    rc, out = run({**WITHIN, "org.example.SlowTest": (40.0, [("e", 40.0)])}, LIMITS)
    check("a class allowed more, within it: exit 0", rc == 0, out)
    check("its allowance is shown beside it", "org.example.SlowTest  (allowed 45 s)" in out, out)
    rc, out = run({**WITHIN, "org.example.SlowTest": (50.0, [("e", 50.0)])}, LIMITS)
    check("over its own limit: exit 1", rc == 1, out)
    check("with its reason", "SlowTest: 50.0 s, over its limit of 45 s (two processes of the program, each started in full)" in out, out)


def test_the_suite_over_its_budget_fails_though_every_class_is_within():
    many = {f"org.example.Part{i}Test": (15.0, [("x", 15.0)]) for i in range(8)}
    rc, out = run(many, LIMITS)
    check("the suite over its budget: exit 1", rc == 1, out)
    check("said with its time and the budget", "the suite: 120.0 s, over its budget of 100 s" in out, out)


def test_an_allowance_for_a_class_no_report_has_is_said():
    rc, out = run(WITHIN, LIMITS)
    check("an allowance nobody uses is said", "Allowed more in the limits file, and in no report: SlowTest" in out, out)


def test_nothing_to_read_is_never_a_pass():
    rc, out = run({}, LIMITS)
    check("no report: exit 2", rc == 2 and "no test report" in out, out)
    rc, out = run(WITHIN, None)
    check("no limits file: exit 2", rc == 2 and "cannot read the limits file" in out, out)
    rc, out = run(WITHIN, "class 20\n")
    check("a limits file without the suite's budget: exit 2", rc == 2, out)
    rc, out = run(WITHIN, "suite 100\nclass 20\nSlowTest 45\n")
    check("a class allowed more without its reason: exit 2", rc == 2 and "says why" in out, out)
    rc, out = run(WITHIN, "suite a-lot\nclass 20\n")
    check("a limit that is not a number: exit 2", rc == 2 and "not a number" in out, out)


def main() -> int:
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    for t in tests:
        t()
    if FAILURES:
        print(f"{len(FAILURES)} check(s) failed:\n")
        for f in FAILURES:
            print(f + "\n")
        return 1
    print(f"all {len(tests)} tests of suite-time.py passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
